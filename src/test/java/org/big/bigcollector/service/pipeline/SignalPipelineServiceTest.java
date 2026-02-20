package org.big.bigcollector.service.pipeline;

import org.big.bigcollector.config.OpenAiConfig;
import org.big.bigcollector.dto.pipeline.ExtractedProblem;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.ProblemVaultEntry;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.ProblemVaultEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignalPipelineServiceTest {

    @Mock
    private CollectorSignalRepository signalRepository;

    @Mock
    private ProblemVaultEntryRepository vaultRepository;

    @Mock
    private LlmProblemExtractor extractor;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ProblemDeduplicator deduplicator;

    @Mock
    private VaultScoringService scoringService;

    @Mock
    private OpenAiConfig openAiConfig;

    private SignalPipelineService pipelineService;

    @BeforeEach
    void setUp() {
        pipelineService = new SignalPipelineService(
                signalRepository, vaultRepository, extractor,
                embeddingService, deduplicator, scoringService, openAiConfig);
    }

    @Test
    void processUnprocessedSignals_notConfigured_returnsError() {
        when(openAiConfig.isConfigured()).thenReturn(false);

        Map<String, Object> result = pipelineService.processUnprocessedSignals();

        assertThat(result).containsEntry("status", "SKIPPED");
        assertThat(result).containsKey("error");
        verifyNoInteractions(signalRepository);
    }

    @Test
    void processUnprocessedSignals_noSignals_returnsCompleted() {
        when(openAiConfig.isConfigured()).thenReturn(true);
        when(signalRepository.findByProcessedFalseOrderByCreatedAtAsc())
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = pipelineService.processUnprocessedSignals();

        assertThat(result).containsEntry("status", "COMPLETED");
        assertThat(result).containsEntry("totalSignals", 0);
        assertThat(result).containsEntry("processed", 0);
    }

    @Test
    void processUnprocessedSignals_withValidSignals_processesAll() {
        when(openAiConfig.isConfigured()).thenReturn(true);

        CollectorSignal signal = CollectorSignal.builder()
                .id(1L)
                .sourceType(SourceType.HACKER_NEWS)
                .rawText("some text about problems")
                .createdAt(Instant.now())
                .build();

        when(signalRepository.findByProcessedFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(signal));

        ExtractedProblem extracted = ExtractedProblem.builder()
                .hasProblem(true)
                .title("A valid problem title here")
                .description("A description")
                .problemType("automation")
                .build();
        when(extractor.extract(any())).thenReturn(extracted);
        when(embeddingService.generateEmbedding(anyString(), anyString()))
                .thenReturn(new float[]{0.1f, 0.2f});

        ProblemVaultEntry newEntry = ProblemVaultEntry.builder()
                .title("A valid problem title here")
                .sourceCount(1)
                .evidence(new ArrayList<>())
                .build();
        when(deduplicator.deduplicate(any(), any(), any()))
                .thenReturn(new ProblemDeduplicator.DeduplicationResult(newEntry, true));

        Map<String, Object> result = pipelineService.processUnprocessedSignals();

        assertThat(result).containsEntry("status", "COMPLETED");
        assertThat(result).containsEntry("processed", 1);
        assertThat(result).containsEntry("problemsExtracted", 1);
        assertThat(result).containsEntry("errors", 0);

        verify(scoringService).scoreEntry(newEntry);
        verify(vaultRepository).save(newEntry);
        verify(signalRepository).save(signal);
        assertThat(signal.getProcessed()).isTrue();
    }

    @Test
    void processUnprocessedSignals_invalidExtraction_marksProcessed() {
        when(openAiConfig.isConfigured()).thenReturn(true);

        CollectorSignal signal = CollectorSignal.builder()
                .id(1L)
                .sourceType(SourceType.HACKER_NEWS)
                .rawText("not a business problem")
                .createdAt(Instant.now())
                .build();

        when(signalRepository.findByProcessedFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(signal));

        ExtractedProblem noProblem = ExtractedProblem.builder()
                .hasProblem(false)
                .build();
        when(extractor.extract(any())).thenReturn(noProblem);

        Map<String, Object> result = pipelineService.processUnprocessedSignals();

        assertThat(result).containsEntry("noProblem", 1);
        assertThat(result).containsEntry("problemsExtracted", 0);
        verify(signalRepository).save(signal);
        assertThat(signal.getProcessed()).isTrue();
        verifyNoInteractions(embeddingService, deduplicator, scoringService, vaultRepository);
    }

    @Test
    void processUnprocessedSignals_extractorThrows_countsError() {
        when(openAiConfig.isConfigured()).thenReturn(true);

        CollectorSignal signal = CollectorSignal.builder()
                .id(1L)
                .sourceType(SourceType.HACKER_NEWS)
                .rawText("text")
                .createdAt(Instant.now())
                .build();

        when(signalRepository.findByProcessedFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(signal));
        when(extractor.extract(any())).thenThrow(new RuntimeException("LLM API error"));

        Map<String, Object> result = pipelineService.processUnprocessedSignals();

        assertThat(result).containsEntry("errors", 1);
        assertThat(result).containsEntry("processed", 0);
        // Signal is marked as failed
        verify(signalRepository).save(signal);
        assertThat(signal.getError()).isEqualTo("LLM API error");
    }

    @Test
    void processUnprocessedSignals_duplicateSignal_skipsScoring() {
        when(openAiConfig.isConfigured()).thenReturn(true);

        CollectorSignal signal = CollectorSignal.builder()
                .id(1L)
                .sourceType(SourceType.REDDIT)
                .rawText("duplicate problem text")
                .createdAt(Instant.now())
                .build();

        when(signalRepository.findByProcessedFalseOrderByCreatedAtAsc())
                .thenReturn(List.of(signal));

        ExtractedProblem extracted = ExtractedProblem.builder()
                .hasProblem(true)
                .title("A valid problem title here")
                .description("A description")
                .problemType("workflow")
                .build();
        when(extractor.extract(any())).thenReturn(extracted);
        when(embeddingService.generateEmbedding(anyString(), anyString()))
                .thenReturn(new float[]{0.1f});

        ProblemVaultEntry existingEntry = ProblemVaultEntry.builder()
                .id(42L)
                .title("Existing problem")
                .sourceCount(2)
                .evidence(new ArrayList<>())
                .build();
        when(deduplicator.deduplicate(any(), any(), any()))
                .thenReturn(new ProblemDeduplicator.DeduplicationResult(existingEntry, false));

        Map<String, Object> result = pipelineService.processUnprocessedSignals();

        assertThat(result).containsEntry("processed", 1);
        verify(scoringService, never()).scoreEntry(any());
        verify(vaultRepository).save(existingEntry);
    }

    @Test
    void isRunning_initiallyFalse() {
        assertThat(pipelineService.isRunning()).isFalse();
    }
}
