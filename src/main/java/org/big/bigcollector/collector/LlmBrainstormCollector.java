package org.big.bigcollector.collector;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.config.AnthropicConfig;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.CollectorTargetRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Slf4j
public class LlmBrainstormCollector implements SourceCollector {

    private static final Model HAIKU_MODEL = Model.CLAUDE_3_HAIKU_20240307;
    private static final int MAX_TOKENS = 4096;

    private static final String SYSTEM_PROMPT = """
            You are a business problem analyst. Your task is to brainstorm real, specific business problems \
            in a given industry that could be solved with software. Focus on problems that:
            - Are experienced by real businesses or professionals
            - Have clear pain points and willingness to pay
            - Could be addressed with a SaaS or software product
            - Are specific enough to build a product around

            Return your response as a JSON array of problem objects. Each object must have these fields:
            - title: A concise problem title (5-10 words)
            - description: 2-3 sentence description of the problem
            - target_customer: Who experiences this problem
            - problem_type: One of "workflow", "communication", "data", "compliance", "cost", "automation"
            - monetization_model: One of "subscription", "usage_based", "freemium", "marketplace"
            - estimated_pain_intensity: One of "high", "medium", "low"

            Return ONLY the JSON array, no other text.
            """;

    private final CollectorTargetRepository targetRepository;
    private final CollectorSignalRepository signalRepository;
    private final AnthropicConfig anthropicConfig;
    private final ObjectMapper objectMapper;

    public LlmBrainstormCollector(CollectorTargetRepository targetRepository,
                                   CollectorSignalRepository signalRepository,
                                   AnthropicConfig anthropicConfig,
                                   ObjectMapper objectMapper) {
        this.targetRepository = targetRepository;
        this.signalRepository = signalRepository;
        this.anthropicConfig = anthropicConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.LLM_BRAINSTORM;
    }

    @Override
    public CollectionResult collect(CollectorConfig config) {
        Instant start = Instant.now();
        int itemsCollected = 0;
        int duplicatesSkipped = 0;

        // Graceful skip if API key not configured
        if (!anthropicConfig.isConfigured()) {
            log.warn("Anthropic API key not configured. Skipping LLM Brainstorm collection.");
            return CollectionResult.builder()
                    .sourceType(SourceType.LLM_BRAINSTORM)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(0)
                    .duplicatesSkipped(0)
                    .duration(Duration.between(start, Instant.now()))
                    .build();
        }

        List<CollectorTarget> targets = targetRepository
                .findBySourceTypeAndEnabledTrue(SourceType.LLM_BRAINSTORM);

        log.info("LLM Brainstorm collection started with {} targets", targets.size());

        try {
            for (CollectorTarget target : targets) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("LLM Brainstorm collection interrupted");
                    break;
                }

                String industry = target.getTargetValue();
                log.debug("LLM Brainstorm generating problems for industry: {}", industry);

                String userMessage = "Generate 5-8 specific business problems in the " + industry + " industry that could be solved with software.";

                String aiResponse = callClaude(userMessage);
                if (aiResponse == null || aiResponse.isBlank()) {
                    log.warn("LLM Brainstorm empty response for industry: {}", industry);
                    continue;
                }

                List<Map<String, Object>> problems = parseProblems(aiResponse);
                if (problems == null || problems.isEmpty()) {
                    log.warn("LLM Brainstorm no parseable problems for industry: {}", industry);
                    continue;
                }

                for (Map<String, Object> problem : problems) {
                    String title = (String) problem.getOrDefault("title", "");
                    if (title.isBlank()) continue;

                    String titleHash = hashString(title.toLowerCase().trim());
                    String sourceId = "llm_" + industry.toLowerCase().replace(" ", "_") + "_" + titleHash;

                    if (signalRepository.existsBySourceTypeAndSourceId(SourceType.LLM_BRAINSTORM, sourceId)) {
                        duplicatesSkipped++;
                        continue;
                    }

                    // Add industry and metadata to the problem data
                    problem.put("industry", industry);
                    problem.put("confidence", "ai_predicted");

                    String rawJson = buildRawJson(problem);

                    CollectorSignal signal = CollectorSignal.builder()
                            .sourceType(SourceType.LLM_BRAINSTORM)
                            .sourceId(sourceId)
                            .rawText(rawJson)
                            .build();
                    signalRepository.save(signal);
                    itemsCollected++;
                }
            }

            log.info("LLM Brainstorm collection completed: {} items, {} duplicates skipped", itemsCollected, duplicatesSkipped);

            return CollectionResult.builder()
                    .sourceType(SourceType.LLM_BRAINSTORM)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .duration(Duration.between(start, Instant.now()))
                    .build();

        } catch (Exception e) {
            log.error("LLM Brainstorm collection failed: {}", e.getMessage(), e);
            return CollectionResult.builder()
                    .sourceType(SourceType.LLM_BRAINSTORM)
                    .status(CollectorStatus.FAILED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .duration(Duration.between(start, Instant.now()))
                    .error(e.getMessage())
                    .build();
        }
    }

    String callClaude(String userMessage) {
        AnthropicClient client = anthropicConfig.getClient();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(HAIKU_MODEL)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM_PROMPT)
                .messages(List.of(
                        MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(userMessage)
                                .build()
                ))
                .build();

        Message response = client.messages().create(params);

        return response.content().stream()
                .map(this::extractText)
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining());
    }

    private String extractText(ContentBlock block) {
        Optional<TextBlock> textBlock = block.text();
        return textBlock.map(TextBlock::text).orElse(null);
    }

    List<Map<String, Object>> parseProblems(String aiResponse) {
        try {
            String json = extractJsonArray(aiResponse);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM brainstorm response: {}", e.getMessage());
            return null;
        }
    }

    private String extractJsonArray(String response) {
        String trimmed = response.trim();

        // Handle markdown code block
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                trimmed = trimmed.substring(start, end).trim();
            }
        } else if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            int lineEnd = trimmed.indexOf('\n', start);
            if (lineEnd > start) start = lineEnd + 1;
            int end = trimmed.indexOf("```", start);
            if (end > start) {
                trimmed = trimmed.substring(start, end).trim();
            }
        }

        // Find array start
        int arrayStart = trimmed.indexOf('[');
        if (arrayStart >= 0) {
            return trimmed.substring(arrayStart);
        }

        return trimmed;
    }

    private String hashString(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    private String buildRawJson(Map<String, Object> problem) {
        try {
            Map<String, Object> data = new LinkedHashMap<>(problem);
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize LLM brainstorm problem: {}", e.getMessage());
            return "{}";
        }
    }
}
