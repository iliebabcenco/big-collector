package org.big.bigcollector.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.dto.github.GitHubSearchResponse;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.CollectorTargetRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class GitHubIssueCollector implements SourceCollector {

    private static final int PER_PAGE = 30;
    private static final int MAX_PAGES = 3;
    private static final long RATE_LIMIT_DELAY_MS = 2000;

    private final CollectorTargetRepository targetRepository;
    private final CollectorSignalRepository signalRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public GitHubIssueCollector(CollectorTargetRepository targetRepository,
                                CollectorSignalRepository signalRepository,
                                @Qualifier("githubWebClient") WebClient webClient,
                                ObjectMapper objectMapper) {
        this.targetRepository = targetRepository;
        this.signalRepository = signalRepository;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.GITHUB;
    }

    @Override
    public CollectionResult collect(CollectorConfig config) {
        Instant start = Instant.now();
        int itemsCollected = 0;
        int duplicatesSkipped = 0;
        String lastCursor = config.getLastCursor();
        int maxItems = config.getMaxItems() != null ? config.getMaxItems() : 100;

        List<CollectorTarget> targets = targetRepository
                .findBySourceTypeAndEnabledTrue(SourceType.GITHUB);

        log.info("GitHub collection started with {} targets, maxItems={}", targets.size(), maxItems);

        try {
            for (CollectorTarget target : targets) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("GitHub collection interrupted");
                    break;
                }
                if (itemsCollected >= maxItems) break;

                String query = buildSearchQuery(target);
                log.debug("GitHub searching: {}", query);

                for (int page = 1; page <= MAX_PAGES && itemsCollected < maxItems; page++) {
                    GitHubSearchResponse response = fetchPage(query, page);
                    if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                        break;
                    }

                    for (GitHubSearchResponse.GitHubIssue issue : response.getItems()) {
                        if (itemsCollected >= maxItems) break;

                        String sourceId = String.valueOf(issue.getId());
                        if (signalRepository.existsBySourceTypeAndSourceId(SourceType.GITHUB, sourceId)) {
                            duplicatesSkipped++;
                            continue;
                        }

                        String rawJson = buildRawJson(issue);
                        CollectorSignal signal = CollectorSignal.builder()
                                .sourceType(SourceType.GITHUB)
                                .sourceId(sourceId)
                                .rawText(rawJson)
                                .build();
                        signalRepository.save(signal);
                        itemsCollected++;
                    }

                    lastCursor = String.valueOf(page);

                    // Rate limit delay between requests
                    try { Thread.sleep(RATE_LIMIT_DELAY_MS); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("GitHub collection completed: {} items, {} duplicates skipped", itemsCollected, duplicatesSkipped);

            return CollectionResult.builder()
                    .sourceType(SourceType.GITHUB)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .lastCursor(lastCursor)
                    .duration(Duration.between(start, Instant.now()))
                    .build();

        } catch (Exception e) {
            log.error("GitHub collection failed: {}", e.getMessage(), e);
            return CollectionResult.builder()
                    .sourceType(SourceType.GITHUB)
                    .status(CollectorStatus.FAILED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .lastCursor(lastCursor)
                    .duration(Duration.between(start, Instant.now()))
                    .error(e.getMessage())
                    .build();
        }
    }

    private String buildSearchQuery(CollectorTarget target) {
        String value = target.getTargetValue();
        return switch (target.getTargetType()) {
            case "LABEL" -> "is:issue is:open label:\"" + value + "\" reactions:>10 sort:reactions-+1";
            case "TOPIC" -> "is:issue is:open \"" + value + "\" reactions:>5 sort:reactions-+1";
            default -> "is:issue is:open \"" + value + "\"";
        };
    }

    private GitHubSearchResponse fetchPage(String query, int page) {
        int retries = 2;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/search/issues")
                                .queryParam("q", query)
                                .queryParam("per_page", PER_PAGE)
                                .queryParam("page", page)
                                .build())
                        .retrieve()
                        .bodyToMono(GitHubSearchResponse.class)
                        .block(Duration.ofSeconds(30));
            } catch (WebClientResponseException.Forbidden e) {
                // GitHub returns 403 for rate limiting
                log.warn("GitHub rate limited (attempt {}/{}), waiting...", attempt + 1, retries + 1);
                if (attempt < retries) {
                    try { Thread.sleep(60_000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during rate limit backoff", ie);
                    }
                } else {
                    throw e;
                }
            } catch (Exception e) {
                log.warn("GitHub API request failed (attempt {}/{}): {}", attempt + 1, retries + 1, e.getMessage());
                if (attempt < retries) {
                    try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry backoff", ie);
                    }
                } else {
                    throw e;
                }
            }
        }
        return null;
    }

    private String buildRawJson(GitHubSearchResponse.GitHubIssue issue) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", issue.getTitle());
            data.put("body", issue.getBody() != null ? truncate(issue.getBody(), 5000) : "");
            data.put("reactions", issue.getReactions() != null ? issue.getReactions().getTotalCount() : 0);
            data.put("reactions_plus_one", issue.getReactions() != null ? issue.getReactions().getPlusOne() : 0);
            data.put("comments", issue.getComments());
            data.put("labels", issue.getLabels() != null
                    ? issue.getLabels().stream().map(GitHubSearchResponse.GitHubLabel::getName).collect(Collectors.toList())
                    : List.of());
            data.put("repo", extractRepoName(issue.getRepositoryUrl()));
            data.put("url", issue.getHtmlUrl());
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize GitHub issue: {}", e.getMessage());
            return "{}";
        }
    }

    private String extractRepoName(String repositoryUrl) {
        if (repositoryUrl == null) return "";
        // https://api.github.com/repos/owner/name -> owner/name
        int idx = repositoryUrl.indexOf("/repos/");
        return idx >= 0 ? repositoryUrl.substring(idx + 7) : repositoryUrl;
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
