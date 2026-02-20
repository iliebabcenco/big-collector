package org.big.bigcollector.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class AnthropicConfig {

    @Value("${anthropic.api-key:}")
    private String apiKey;

    private AnthropicClient client;

    @PostConstruct
    public void init() {
        if (apiKey != null && !apiKey.isBlank()) {
            this.client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
            log.info("Anthropic client initialized for LLM Brainstorm collector");
        } else {
            log.warn("Anthropic API key not configured. LLM Brainstorm collector will be skipped.");
        }
    }

    public boolean isConfigured() {
        return client != null;
    }

    public AnthropicClient getClient() {
        return client;
    }
}
