package org.big.bigcollector.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import org.big.bigcollector.config.OpenAiConfig;
import org.big.bigcollector.dto.pipeline.ExtractedProblem;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.LlmPromptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmProblemExtractorTest {

    @Mock
    private OpenAIClient openAIClient;

    @Mock
    private OpenAiConfig openAiConfig;

    @Mock
    private LlmPromptRepository promptRepository;

    private LlmProblemExtractor extractor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        extractor = new LlmProblemExtractor(openAIClient, openAiConfig, promptRepository, objectMapper);
    }

    @Test
    void extract_notConfigured_returnsNoProblem() {
        when(openAiConfig.isConfigured()).thenReturn(false);

        CollectorSignal signal = CollectorSignal.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .rawText("some text")
                .build();

        ExtractedProblem result = extractor.extract(signal);

        assertThat(result.isHasProblem()).isFalse();
    }

    @Test
    void parseResponse_validJson_returnsProblem() {
        String json = """
                {
                    "has_problem": true,
                    "title": "Manual invoice processing wastes hours",
                    "description": "Small businesses spend 5+ hours per week manually processing invoices and receipts.",
                    "problem_type": "automation",
                    "industry": "Finance",
                    "target_customer": "Small business owners",
                    "pain_intensity": "high",
                    "monetization_potential": "high",
                    "monetization_model": "subscription",
                    "willingness_to_pay_signal": "Users report paying contractors to do this",
                    "key_quotes": ["I spend hours every week on invoices"],
                    "source_url": "https://example.com"
                }
                """;

        ExtractedProblem result = extractor.parseResponse(json);

        assertThat(result.isHasProblem()).isTrue();
        assertThat(result.getTitle()).isEqualTo("Manual invoice processing wastes hours");
        assertThat(result.getProblemType()).isEqualTo("automation");
        assertThat(result.getIndustry()).isEqualTo("Finance");
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void parseResponse_noProblem_returnsHasProblemFalse() {
        String json = """
                {"has_problem": false}
                """;

        ExtractedProblem result = extractor.parseResponse(json);

        assertThat(result.isHasProblem()).isFalse();
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void parseResponse_markdownWrappedJson_parsesCorrectly() {
        String response = """
                ```json
                {
                    "has_problem": true,
                    "title": "Project management tool fragmentation",
                    "description": "Teams use multiple PM tools that don't sync, causing lost tasks.",
                    "problem_type": "integration",
                    "industry": "Technology",
                    "target_customer": "Engineering teams",
                    "pain_intensity": "medium",
                    "monetization_potential": "high",
                    "monetization_model": "subscription",
                    "key_quotes": ["We lose tasks between Jira and Asana"]
                }
                ```
                """;

        ExtractedProblem result = extractor.parseResponse(response);

        assertThat(result.isHasProblem()).isTrue();
        assertThat(result.getTitle()).contains("fragmentation");
    }

    @Test
    void parseResponse_malformedJson_returnsNoProblem() {
        String malformed = "This is not JSON at all";

        ExtractedProblem result = extractor.parseResponse(malformed);

        assertThat(result.isHasProblem()).isFalse();
    }

    @Test
    void extractedProblem_validation_titleTooShort() {
        ExtractedProblem problem = ExtractedProblem.builder()
                .hasProblem(true)
                .title("Short")  // < 10 chars
                .description("Some description")
                .problemType("workflow")
                .build();

        assertThat(problem.isValid()).isFalse();
    }

    @Test
    void extractedProblem_validation_missingProblemType() {
        ExtractedProblem problem = ExtractedProblem.builder()
                .hasProblem(true)
                .title("A valid title that is long enough")
                .description("Some description")
                .problemType(null)
                .build();

        assertThat(problem.isValid()).isFalse();
    }
}
