package org.big.bigcollector.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.big.bigcollector.config.AnthropicConfig;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.CollectorTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmBrainstormCollectorTest {

    @Mock
    private CollectorTargetRepository targetRepository;

    @Mock
    private CollectorSignalRepository signalRepository;

    @Mock
    private AnthropicConfig anthropicConfig;

    private LlmBrainstormCollector collector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        collector = new LlmBrainstormCollector(targetRepository, signalRepository, anthropicConfig, objectMapper);
    }

    @Test
    void getSourceType_returnsLlmBrainstorm() {
        assertThat(collector.getSourceType()).isEqualTo(SourceType.LLM_BRAINSTORM);
    }

    @Test
    void collect_successfulBrainstorm_savesProblems() {
        String aiResponse = """
                [
                    {
                        "title": "Patient Data Interoperability Gap",
                        "description": "Healthcare providers struggle to share patient records across different EHR systems.",
                        "target_customer": "Healthcare administrators",
                        "problem_type": "data",
                        "monetization_model": "subscription",
                        "estimated_pain_intensity": "high"
                    },
                    {
                        "title": "Clinical Trial Recruitment Bottleneck",
                        "description": "Research institutions waste months finding eligible patients for clinical trials.",
                        "target_customer": "Clinical research coordinators",
                        "problem_type": "workflow",
                        "monetization_model": "usage_based",
                        "estimated_pain_intensity": "high"
                    }
                ]
                """;

        when(anthropicConfig.isConfigured()).thenReturn(true);

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.LLM_BRAINSTORM)
                .targetType("INDUSTRY")
                .targetValue("Healthcare")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.LLM_BRAINSTORM))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);

        // Use a spy to mock the Claude API call
        LlmBrainstormCollector spyCollector = spy(collector);
        doReturn(aiResponse).when(spyCollector).callClaude(any());

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.LLM_BRAINSTORM)
                .maxItems(100)
                .build();

        CollectionResult result = spyCollector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(2);
        assertThat(result.duplicatesSkipped()).isEqualTo(0);
        verify(signalRepository, times(2)).save(any());
    }

    @Test
    void collect_noApiKey_skipsGracefully() {
        when(anthropicConfig.isConfigured()).thenReturn(false);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.LLM_BRAINSTORM)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        verify(targetRepository, never()).findBySourceTypeAndEnabledTrue(any());
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_duplicateProblems_skipped() {
        String aiResponse = """
                [
                    {
                        "title": "Existing Problem Title",
                        "description": "This problem was already collected.",
                        "target_customer": "Some customer",
                        "problem_type": "workflow",
                        "monetization_model": "subscription",
                        "estimated_pain_intensity": "medium"
                    }
                ]
                """;

        when(anthropicConfig.isConfigured()).thenReturn(true);

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.LLM_BRAINSTORM)
                .targetType("INDUSTRY")
                .targetValue("Healthcare")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.LLM_BRAINSTORM))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(eq(SourceType.LLM_BRAINSTORM), any()))
                .thenReturn(true);

        LlmBrainstormCollector spyCollector = spy(collector);
        doReturn(aiResponse).when(spyCollector).callClaude(any());

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.LLM_BRAINSTORM)
                .maxItems(100)
                .build();

        CollectionResult result = spyCollector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        assertThat(result.duplicatesSkipped()).isEqualTo(1);
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_malformedAiResponse_handledGracefully() {
        String malformedResponse = "This is not valid JSON at all, just random text from the AI.";

        when(anthropicConfig.isConfigured()).thenReturn(true);

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.LLM_BRAINSTORM)
                .targetType("INDUSTRY")
                .targetValue("Healthcare")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.LLM_BRAINSTORM))
                .thenReturn(List.of(target));

        LlmBrainstormCollector spyCollector = spy(collector);
        doReturn(malformedResponse).when(spyCollector).callClaude(any());

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.LLM_BRAINSTORM)
                .maxItems(100)
                .build();

        CollectionResult result = spyCollector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        verify(signalRepository, never()).save(any());
    }
}
