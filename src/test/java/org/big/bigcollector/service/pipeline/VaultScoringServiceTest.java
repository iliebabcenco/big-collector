package org.big.bigcollector.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import org.big.bigcollector.config.OpenAiConfig;
import org.big.bigcollector.entity.ProblemVaultEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VaultScoringServiceTest {

    @Mock
    private OpenAIClient openAIClient;

    @Mock
    private OpenAiConfig openAiConfig;

    private VaultScoringService scoringService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        scoringService = new VaultScoringService(openAIClient, openAiConfig, objectMapper);
    }

    @Test
    void scoreEntry_notConfigured_skips() {
        when(openAiConfig.isConfigured()).thenReturn(false);

        ProblemVaultEntry entry = ProblemVaultEntry.builder()
                .title("Some problem")
                .description("Some description")
                .sourceCount(1)
                .build();

        scoringService.scoreEntry(entry);

        assertThat(entry.getScoreDemand()).isNull();
        assertThat(entry.getOverallScore()).isNull();
        verifyNoInteractions(openAIClient);
    }

    @Test
    void applyScores_validJson_setsAllScores() {
        String json = """
                {
                    "demand": {"score": 20, "rationale": "High demand"},
                    "pain": {"score": 22, "rationale": "Very painful"},
                    "gap": {"score": 15, "rationale": "Market gap exists"},
                    "timing": {"score": 12, "rationale": "Good timing"},
                    "feasibility": {"score": 10, "rationale": "Feasible MVP"}
                }
                """;

        ProblemVaultEntry entry = ProblemVaultEntry.builder()
                .title("Test problem")
                .description("Test description")
                .sourceCount(1)
                .build();

        scoringService.applyScores(entry, json);

        assertThat(entry.getScoreDemand()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(entry.getScorePain()).isEqualByComparingTo(new BigDecimal("22"));
        assertThat(entry.getScoreGrowth()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(entry.getScoreTractability()).isEqualByComparingTo(new BigDecimal("12"));
        assertThat(entry.getScoreFrequency()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(entry.getOverallScore()).isEqualByComparingTo(new BigDecimal("79"));
    }

    @Test
    void applyScores_scoresClampedToMax() {
        String json = """
                {
                    "demand": {"score": 30, "rationale": "Over max 25"},
                    "pain": {"score": 30, "rationale": "Over max 25"},
                    "gap": {"score": 25, "rationale": "Over max 20"},
                    "timing": {"score": 20, "rationale": "Over max 15"},
                    "feasibility": {"score": 20, "rationale": "Over max 15"}
                }
                """;

        ProblemVaultEntry entry = ProblemVaultEntry.builder()
                .title("Test")
                .description("Test")
                .sourceCount(1)
                .build();

        scoringService.applyScores(entry, json);

        assertThat(entry.getScoreDemand()).isEqualByComparingTo(new BigDecimal("25"));
        assertThat(entry.getScorePain()).isEqualByComparingTo(new BigDecimal("25"));
        assertThat(entry.getScoreGrowth()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(entry.getScoreTractability()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(entry.getScoreFrequency()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(entry.getOverallScore()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void applyScores_negativeScoresClampedToZero() {
        String json = """
                {
                    "demand": {"score": -5, "rationale": "Negative"},
                    "pain": {"score": 0, "rationale": "Zero"},
                    "gap": {"score": 10, "rationale": "Normal"},
                    "timing": {"score": 5, "rationale": "Normal"},
                    "feasibility": {"score": 8, "rationale": "Normal"}
                }
                """;

        ProblemVaultEntry entry = ProblemVaultEntry.builder()
                .title("Test")
                .description("Test")
                .sourceCount(1)
                .build();

        scoringService.applyScores(entry, json);

        assertThat(entry.getScoreDemand()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entry.getScorePain()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void applyScores_markdownWrappedJson_parsesCorrectly() {
        String response = """
                ```json
                {
                    "demand": {"score": 18, "rationale": "Good demand"},
                    "pain": {"score": 20, "rationale": "High pain"},
                    "gap": {"score": 12, "rationale": "Gap exists"},
                    "timing": {"score": 10, "rationale": "Good timing"},
                    "feasibility": {"score": 11, "rationale": "Feasible"}
                }
                ```
                """;

        ProblemVaultEntry entry = ProblemVaultEntry.builder()
                .title("Test")
                .description("Test")
                .sourceCount(1)
                .build();

        scoringService.applyScores(entry, response);

        assertThat(entry.getScoreDemand()).isEqualByComparingTo(new BigDecimal("18"));
        assertThat(entry.getOverallScore()).isEqualByComparingTo(new BigDecimal("71"));
    }

    @Test
    void applyScores_malformedJson_doesNotCrash() {
        ProblemVaultEntry entry = ProblemVaultEntry.builder()
                .title("Test")
                .description("Test")
                .sourceCount(1)
                .build();

        scoringService.applyScores(entry, "not valid json at all");

        assertThat(entry.getScoreDemand()).isNull();
        assertThat(entry.getOverallScore()).isNull();
    }
}
