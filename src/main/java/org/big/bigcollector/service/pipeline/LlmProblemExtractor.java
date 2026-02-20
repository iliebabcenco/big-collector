package org.big.bigcollector.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.config.OpenAiConfig;
import org.big.bigcollector.dto.pipeline.ExtractedProblem;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.LlmPrompt;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.LlmPromptRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class LlmProblemExtractor {

    private static final ChatModel MODEL = ChatModel.GPT_4O_MINI;
    private static final String PROMPT_NAME = "problem_extraction_v1";

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a business problem extraction expert. Analyze the following raw text from an online source \
            and extract any clear business problem that could be solved with a software product.

            Return a JSON object with these fields:
            - has_problem (boolean): true if a clear, actionable business problem is described
            - title (string): concise problem title, 10-200 characters
            - description (string): 2-3 sentence description of the problem
            - problem_type (string): one of "workflow", "communication", "data", "compliance", "cost", "automation", "integration", "quality"
            - industry (string): the industry affected
            - target_customer (string): who experiences this problem
            - pain_intensity (string): "high", "medium", or "low"
            - monetization_potential (string): "high", "medium", or "low"
            - monetization_model (string): one of "subscription", "usage_based", "freemium", "marketplace", "one_time"
            - willingness_to_pay_signal (string): evidence that people would pay for a solution
            - key_quotes (array of strings): 1-3 direct quotes from the text that evidence the problem
            - source_url (string): URL from the source data if available, or empty string

            If no clear business problem is found, return: {"has_problem": false}

            IMPORTANT: Return ONLY valid JSON, no markdown or extra text.
            """;

    private static final Map<SourceType, String> SOURCE_HINTS = Map.of(
            SourceType.APP_STORE, "\nFocus on: negative reviews, missing features, broken workflows, frustrations. Rating <= 3 stars indicates real pain.",
            SourceType.GITHUB, "\nFocus on: feature requests with many reactions suggest demand. Consider if this could be a standalone product vs just a feature.",
            SourceType.UPWORK, "\nFocus on: the budget signals willingness-to-pay. Extract the automatable business problem behind the freelance request.",
            SourceType.HACKER_NEWS, "\nFocus on: 'I wish there was...' and 'Ask HN' posts indicate unmet needs. Community upvotes signal demand.",
            SourceType.REDDIT, "\nFocus on: complaints, wishlists, and 'someone should build' posts. Upvotes and comments indicate community interest.",
            SourceType.PRODUCT_HUNT, "\nFocus on: constructive criticism and missing features in product comments. These reveal gaps in existing solutions.",
            SourceType.LLM_BRAINSTORM, "\nThis is an AI-generated problem brainstorm. Validate the problem is specific and actionable, not generic."
    );

    private final OpenAIClient openAIClient;
    private final OpenAiConfig openAiConfig;
    private final LlmPromptRepository promptRepository;
    private final ObjectMapper objectMapper;

    public LlmProblemExtractor(OpenAIClient openAIClient,
                                OpenAiConfig openAiConfig,
                                LlmPromptRepository promptRepository,
                                ObjectMapper objectMapper) {
        this.openAIClient = openAIClient;
        this.openAiConfig = openAiConfig;
        this.promptRepository = promptRepository;
        this.objectMapper = objectMapper;
    }

    public ExtractedProblem extract(CollectorSignal signal) {
        if (!openAiConfig.isConfigured()) {
            log.warn("OpenAI not configured, cannot extract problem");
            return ExtractedProblem.builder().hasProblem(false).build();
        }

        String systemPrompt = loadSystemPrompt(signal.getSourceType());
        String userMessage = "Source: " + signal.getSourceType().name() + "\n\nRaw text:\n" + signal.getRawText();

        try {
            String response = callOpenAi(systemPrompt, userMessage);
            return parseResponse(response);
        } catch (Exception e) {
            log.error("Failed to extract problem from signal {}: {}", signal.getId(), e.getMessage());
            return ExtractedProblem.builder().hasProblem(false).build();
        }
    }

    String callOpenAi(String systemPrompt, String userMessage) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(MODEL)
                .maxCompletionTokens(1000L)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userMessage)
                .build();

        ChatCompletion completion = openAIClient.chat().completions().create(params);

        return completion.choices().stream()
                .findFirst()
                .flatMap(choice -> choice.message().content())
                .orElseThrow(() -> new RuntimeException("Empty OpenAI response"));
    }

    ExtractedProblem parseResponse(String response) {
        try {
            String json = extractJson(response);
            return objectMapper.readValue(json, ExtractedProblem.class);
        } catch (Exception e) {
            log.warn("Failed to parse extraction response: {}", e.getMessage());
            return ExtractedProblem.builder().hasProblem(false).build();
        }
    }

    private String loadSystemPrompt(SourceType sourceType) {
        // Try DB first
        Optional<LlmPrompt> dbPrompt = promptRepository.findByPromptNameAndActiveTrue(PROMPT_NAME);
        String basePrompt = dbPrompt.map(LlmPrompt::getSystemPrompt).orElse(DEFAULT_SYSTEM_PROMPT);

        // Append source-specific hint
        String hint = SOURCE_HINTS.getOrDefault(sourceType, "");
        return basePrompt + hint;
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.indexOf("```", start);
            if (end > start) return trimmed.substring(start, end).trim();
        } else if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            int lineEnd = trimmed.indexOf('\n', start);
            if (lineEnd > start) start = lineEnd + 1;
            int end = trimmed.indexOf("```", start);
            if (end > start) return trimmed.substring(start, end).trim();
        }
        int objStart = trimmed.indexOf('{');
        if (objStart >= 0) return trimmed.substring(objStart);
        return trimmed;
    }
}
