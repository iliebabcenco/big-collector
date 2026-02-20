package org.big.bigcollector.service.pipeline;

import org.big.bigcollector.dto.pipeline.ExtractedProblem;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.ProblemVaultEntry;
import org.big.bigcollector.entity.enums.SourceType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProblemDeduplicatorTest {

    @Mock
    private ProblemVaultEntryRepository vaultRepository;

    @Mock
    private LlmDuplicateVerifier duplicateVerifier;

    private ProblemDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        deduplicator = new ProblemDeduplicator(vaultRepository, duplicateVerifier);
    }

    private ExtractedProblem buildExtractedProblem() {
        return ExtractedProblem.builder()
                .hasProblem(true)
                .title("Manual invoice processing wastes hours")
                .description("Small businesses spend 5+ hours per week manually processing invoices.")
                .problemType("automation")
                .industry("Finance")
                .targetCustomer("Small business owners")
                .sourceUrl("https://example.com")
                .keyQuotes(List.of("I spend hours on invoices"))
                .build();
    }

    private CollectorSignal buildSignal() {
        return CollectorSignal.builder()
                .id(1L)
                .sourceType(SourceType.HACKER_NEWS)
                .rawText("some raw text")
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void deduplicate_nullEmbedding_createsNewEntry() {
        ExtractedProblem extracted = buildExtractedProblem();
        CollectorSignal signal = buildSignal();

        ProblemDeduplicator.DeduplicationResult result = deduplicator.deduplicate(extracted, null, signal);

        assertThat(result.isNew()).isTrue();
        assertThat(result.entry().getTitle()).isEqualTo("Manual invoice processing wastes hours");
        assertThat(result.entry().getSourceCount()).isEqualTo(1);
        assertThat(result.entry().getConfidence()).isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(result.entry().getEvidence()).hasSize(1);
        verifyNoInteractions(vaultRepository);
    }

    @Test
    void deduplicate_noSimilarFound_createsNewEntry() {
        when(vaultRepository.findSimilarByEmbedding(anyString(), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());

        ExtractedProblem extracted = buildExtractedProblem();
        CollectorSignal signal = buildSignal();
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};

        ProblemDeduplicator.DeduplicationResult result = deduplicator.deduplicate(extracted, embedding, signal);

        assertThat(result.isNew()).isTrue();
        assertThat(result.entry().getTitle()).isEqualTo("Manual invoice processing wastes hours");
    }

    @Test
    void deduplicate_definiteDuplicate_mergesEvidence() {
        ProblemVaultEntry existing = ProblemVaultEntry.builder()
                .id(42L)
                .title("Invoice processing is slow")
                .description("Existing description")
                .sourceCount(2)
                .confidence(new BigDecimal("0.50"))
                .evidence(new ArrayList<>())
                .build();

        when(vaultRepository.findSimilarByEmbedding(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(existing));
        when(vaultRepository.getDistanceTo(eq(42L), anyString()))
                .thenReturn(0.05); // < 0.10 = definite duplicate

        ExtractedProblem extracted = buildExtractedProblem();
        CollectorSignal signal = buildSignal();
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};

        ProblemDeduplicator.DeduplicationResult result = deduplicator.deduplicate(extracted, embedding, signal);

        assertThat(result.isNew()).isFalse();
        assertThat(result.entry().getId()).isEqualTo(42L);
        assertThat(result.entry().getSourceCount()).isEqualTo(3);
        assertThat(result.entry().getConfidence()).isEqualByComparingTo(new BigDecimal("0.75"));
        assertThat(result.entry().getEvidence()).hasSize(1);
        verifyNoInteractions(duplicateVerifier);
    }

    @Test
    void deduplicate_borderline_llmConfirmsDuplicate_merges() {
        ProblemVaultEntry existing = ProblemVaultEntry.builder()
                .id(42L)
                .title("Invoice processing is slow")
                .description("Existing description")
                .sourceCount(1)
                .confidence(new BigDecimal("0.25"))
                .evidence(new ArrayList<>())
                .build();

        when(vaultRepository.findSimilarByEmbedding(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(existing));
        when(vaultRepository.getDistanceTo(eq(42L), anyString()))
                .thenReturn(0.15); // 0.10-0.20 = borderline
        when(duplicateVerifier.isDuplicate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        ExtractedProblem extracted = buildExtractedProblem();
        CollectorSignal signal = buildSignal();
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};

        ProblemDeduplicator.DeduplicationResult result = deduplicator.deduplicate(extracted, embedding, signal);

        assertThat(result.isNew()).isFalse();
        assertThat(result.entry().getSourceCount()).isEqualTo(2);
        assertThat(result.entry().getConfidence()).isEqualByComparingTo(new BigDecimal("0.50"));
        verify(duplicateVerifier).isDuplicate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void deduplicate_borderline_llmSaysDifferent_createsNew() {
        ProblemVaultEntry existing = ProblemVaultEntry.builder()
                .id(42L)
                .title("Invoice processing is slow")
                .description("Existing description")
                .sourceCount(1)
                .confidence(new BigDecimal("0.25"))
                .evidence(new ArrayList<>())
                .build();

        when(vaultRepository.findSimilarByEmbedding(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(existing));
        when(vaultRepository.getDistanceTo(eq(42L), anyString()))
                .thenReturn(0.15); // borderline
        when(duplicateVerifier.isDuplicate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        ExtractedProblem extracted = buildExtractedProblem();
        CollectorSignal signal = buildSignal();
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};

        ProblemDeduplicator.DeduplicationResult result = deduplicator.deduplicate(extracted, embedding, signal);

        assertThat(result.isNew()).isTrue();
        assertThat(result.entry().getTitle()).isEqualTo("Manual invoice processing wastes hours");
    }

    @Test
    void deduplicate_distanceAboveBorderline_createsNew() {
        ProblemVaultEntry existing = ProblemVaultEntry.builder()
                .id(42L)
                .title("Totally different problem")
                .description("Unrelated")
                .sourceCount(1)
                .confidence(new BigDecimal("0.25"))
                .evidence(new ArrayList<>())
                .build();

        when(vaultRepository.findSimilarByEmbedding(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(existing));
        when(vaultRepository.getDistanceTo(eq(42L), anyString()))
                .thenReturn(0.22); // > 0.20 = new

        ExtractedProblem extracted = buildExtractedProblem();
        CollectorSignal signal = buildSignal();
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};

        ProblemDeduplicator.DeduplicationResult result = deduplicator.deduplicate(extracted, embedding, signal);

        assertThat(result.isNew()).isTrue();
        verifyNoInteractions(duplicateVerifier);
    }

    @Test
    void deduplicate_confidenceProgression() {
        // Test confidence upgrades: 1→0.25, 2→0.50, 3→0.75, 5→0.90
        ProblemVaultEntry entry4Sources = ProblemVaultEntry.builder()
                .id(42L)
                .title("Some problem")
                .description("Some description")
                .sourceCount(4)
                .confidence(new BigDecimal("0.75"))
                .evidence(new ArrayList<>())
                .build();

        when(vaultRepository.findSimilarByEmbedding(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(entry4Sources));
        when(vaultRepository.getDistanceTo(eq(42L), anyString()))
                .thenReturn(0.05); // definite dup

        ExtractedProblem extracted = buildExtractedProblem();
        CollectorSignal signal = buildSignal();
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};

        ProblemDeduplicator.DeduplicationResult result = deduplicator.deduplicate(extracted, embedding, signal);

        assertThat(result.entry().getSourceCount()).isEqualTo(5);
        assertThat(result.entry().getConfidence()).isEqualByComparingTo(new BigDecimal("0.90"));
    }

    @Test
    void deduplicate_evidenceContainsSignalData() {
        ExtractedProblem extracted = buildExtractedProblem();
        CollectorSignal signal = buildSignal();

        ProblemDeduplicator.DeduplicationResult result = deduplicator.deduplicate(extracted, null, signal);

        assertThat(result.entry().getEvidence()).hasSize(1);
        var evidence = result.entry().getEvidence().getFirst();
        assertThat(evidence.getSourceType()).isEqualTo(SourceType.HACKER_NEWS);
        assertThat(evidence.getSourceUrl()).isEqualTo("https://example.com");
        assertThat(evidence.getRawText()).isEqualTo("some raw text");
        assertThat(evidence.getQuoteText()).isEqualTo("I spend hours on invoices");
    }
}
