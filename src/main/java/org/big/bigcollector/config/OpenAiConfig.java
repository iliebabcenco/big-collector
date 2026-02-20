package org.big.bigcollector.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class OpenAiConfig {

    @Value("${openai.api-key:}")
    private String apiKey;

    @PostConstruct
    public void logStatus() {
        if (apiKey != null && !apiKey.isBlank()) {
            log.info("OpenAI API key configured for LLM pipeline");
        } else {
            log.warn("OpenAI API key not configured. LLM pipeline will not work.");
        }
    }

    @Bean
    public OpenAIClient openAIClient() {
        if (apiKey != null && !apiKey.isBlank()) {
            return OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
        }
        // Return a client that will fail on use — services check isConfigured()
        return OpenAIOkHttpClient.builder()
                .apiKey("not-configured")
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
