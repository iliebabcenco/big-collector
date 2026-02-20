package org.big.bigcollector.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Value("${github.token:}")
    private String githubToken;

    @Value("${producthunt.token:}")
    private String productHuntToken;

    @Value("${collector.reddit.user-agent:BIG-Collector/1.0}")
    private String redditUserAgent;

    @Bean("hnWebClient")
    public WebClient hnWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("https://hn.algolia.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean("githubWebClient")
    public WebClient githubWebClient(WebClient.Builder builder) {
        WebClient.Builder b = builder
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github.v3+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "BIG-Collector");

        if (githubToken != null && !githubToken.isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + githubToken);
        }

        return b.build();
    }

    @Bean("redditWebClient")
    public WebClient redditWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("https://www.reddit.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, redditUserAgent)
                .build();
    }

    @Bean("appleRssWebClient")
    public WebClient appleRssWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("https://itunes.apple.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean("productHuntWebClient")
    public WebClient productHuntWebClient(WebClient.Builder builder) {
        WebClient.Builder b = builder
                .baseUrl("https://api.producthunt.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        if (productHuntToken != null && !productHuntToken.isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + productHuntToken);
        }

        return b.build();
    }
}
