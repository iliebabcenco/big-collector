package org.big.bigcollector.service.pipeline;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.config.OpenAiConfig;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LlmDuplicateVerifier {

    private static final ChatModel MODEL = ChatModel.GPT_4O_MINI;

    private static final String SYSTEM_PROMPT = """
            You are a deduplication expert. Given two business problems, determine if they describe \
            the SAME core problem (even if worded differently) or if they are genuinely DIFFERENT problems.

            Respond with ONLY one word: "DUPLICATE" or "DIFFERENT"
            """;

    private final OpenAIClient openAIClient;
    private final OpenAiConfig openAiConfig;

    public LlmDuplicateVerifier(OpenAIClient openAIClient, OpenAiConfig openAiConfig) {
        this.openAIClient = openAIClient;
        this.openAiConfig = openAiConfig;
    }

    public boolean isDuplicate(String title1, String desc1, String title2, String desc2) {
        if (!openAiConfig.isConfigured()) {
            log.warn("OpenAI not configured, defaulting to NOT duplicate for borderline case");
            return false;
        }

        String userMessage = """
                Problem A:
                Title: %s
                Description: %s

                Problem B:
                Title: %s
                Description: %s

                Are these the SAME problem or DIFFERENT problems?
                """.formatted(title1, desc1, title2, desc2);

        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(MODEL)
                    .maxCompletionTokens(10L)
                    .addSystemMessage(SYSTEM_PROMPT)
                    .addUserMessage(userMessage)
                    .build();

            ChatCompletion completion = openAIClient.chat().completions().create(params);

            String response = completion.choices().stream()
                    .findFirst()
                    .flatMap(choice -> choice.message().content())
                    .orElse("DIFFERENT");

            boolean result = response.trim().toUpperCase().contains("DUPLICATE");
            log.debug("LLM dedup verification: '{}' vs '{}' -> {}", title1, title2, result ? "DUPLICATE" : "DIFFERENT");
            return result;
        } catch (Exception e) {
            log.error("LLM dedup verification failed: {}", e.getMessage());
            return false;
        }
    }
}
