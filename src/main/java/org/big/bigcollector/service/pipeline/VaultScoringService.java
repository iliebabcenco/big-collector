package org.big.bigcollector.service.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.config.OpenAiConfig;
import org.big.bigcollector.entity.ProblemVaultEntry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
public class VaultScoringService {

    private static final ChatModel MODEL = ChatModel.GPT_4O_MINI;

    private static final String SYSTEM_PROMPT = """
            You are a business opportunity scoring expert. Score the given business problem using the DPGTF framework.

            Score each dimension on its specific scale:
            - demand (0-25): Is there growing interest/market for this? Consider search trends, community interest, number of people affected.
            - pain (0-25): How painful is this problem? Consider severity, frequency, workarounds, frustration level.
            - gap (0-20): Is the market underserved? Consider existing solutions, their weaknesses, price gaps.
            - timing (0-15): Is now the right time? Consider technology readiness, regulatory changes, behavioral shifts.
            - feasibility (0-15): Can a small team build this? Consider technical complexity, required integrations, time to MVP.

            Also provide a 1-sentence rationale for each score.

            Return ONLY a JSON object with this structure:
            {
              "demand": {"score": 0, "rationale": "..."},
              "pain": {"score": 0, "rationale": "..."},
              "gap": {"score": 0, "rationale": "..."},
              "timing": {"score": 0, "rationale": "..."},
              "feasibility": {"score": 0, "rationale": "..."}
            }
            """;

    private final OpenAIClient openAIClient;
    private final OpenAiConfig openAiConfig;
    private final ObjectMapper objectMapper;

    public VaultScoringService(OpenAIClient openAIClient,
                                OpenAiConfig openAiConfig,
                                ObjectMapper objectMapper) {
        this.openAIClient = openAIClient;
        this.openAiConfig = openAiConfig;
        this.objectMapper = objectMapper;
    }

    public void scoreEntry(ProblemVaultEntry entry) {
        if (!openAiConfig.isConfigured()) {
            log.warn("OpenAI not configured, skipping scoring for: {}", entry.getTitle());
            return;
        }

        String userMessage = """
                Problem: %s
                Description: %s
                Industry: %s
                Target Customer: %s
                Problem Type: %s
                Sources confirming this problem: %d
                """.formatted(
                entry.getTitle(),
                entry.getDescription(),
                entry.getIndustry() != null ? entry.getIndustry() : "Unknown",
                entry.getTargetCustomer() != null ? entry.getTargetCustomer() : "Unknown",
                entry.getProblemType() != null ? entry.getProblemType() : "Unknown",
                entry.getSourceCount()
        );

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(MODEL)
                    .maxCompletionTokens(500L)
                    .addSystemMessage(SYSTEM_PROMPT)
                    .addUserMessage(userMessage)
                    .build();

            ChatCompletion completion = openAIClient.chat().completions().create(params);

            String response = completion.choices().stream()
                    .findFirst()
                    .flatMap(choice -> choice.message().content())
                    .orElseThrow(() -> new RuntimeException("Empty scoring response"));

            applyScores(entry, response);
            log.debug("Scored '{}': overall={}", entry.getTitle(), entry.getOverallScore());

        } catch (Exception e) {
            log.error("Failed to score problem '{}': {}", entry.getTitle(), e.getMessage());
        }
    }

    void applyScores(ProblemVaultEntry entry, String response) {
        try {
            String json = extractJson(response);
            DpgtfScores scores = objectMapper.readValue(json, DpgtfScores.class);

            entry.setScoreDemand(clampScore(scores.demand.score, 25));
            entry.setScorePain(clampScore(scores.pain.score, 25));
            entry.setScoreGrowth(clampScore(scores.gap.score, 20));
            entry.setScoreTractability(clampScore(scores.timing.score, 15));
            entry.setScoreFrequency(clampScore(scores.feasibility.score, 15));

            BigDecimal total = entry.getScoreDemand()
                    .add(entry.getScorePain())
                    .add(entry.getScoreGrowth())
                    .add(entry.getScoreTractability())
                    .add(entry.getScoreFrequency());
            entry.setOverallScore(total);

        } catch (Exception e) {
            log.warn("Failed to parse scoring response: {}", e.getMessage());
        }
    }

    private BigDecimal clampScore(int score, int max) {
        return BigDecimal.valueOf(Math.max(0, Math.min(score, max)));
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) return trimmed.substring(start, end).trim();
        }
        int objStart = trimmed.indexOf('{');
        if (objStart >= 0) return trimmed.substring(objStart);
        return trimmed;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DpgtfScores {
        private DimensionScore demand;
        private DimensionScore pain;
        private DimensionScore gap;
        private DimensionScore timing;
        private DimensionScore feasibility;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class DimensionScore {
        private int score;
        private String rationale;
    }
}
