package org.big.bigcollector.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.dto.reddit.RedditListingResponse;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.CollectorTargetRepository;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class RedditCollector implements SourceCollector {

    private static final int LIMIT = 100;
    private static final int MAX_PAGES = 3;
    private static final long REQUEST_DELAY_MS = 600;
    private static final int MIN_SCORE = 5;
    private static final int MIN_SELFTEXT_LENGTH = 50;

    private final CollectorTargetRepository targetRepository;
    private final CollectorSignalRepository signalRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public RedditCollector(CollectorTargetRepository targetRepository,
                           CollectorSignalRepository signalRepository,
                           @Qualifier("redditWebClient") WebClient webClient,
                           ObjectMapper objectMapper) {
        this.targetRepository = targetRepository;
        this.signalRepository = signalRepository;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.REDDIT;
    }

    @Override
    public CollectionResult collect(CollectorConfig config) {
        Instant start = Instant.now();
        int itemsCollected = 0;
        int duplicatesSkipped = 0;
        String lastCursor = config.getLastCursor();
        int maxItems = config.getMaxItems() != null ? config.getMaxItems() : 100;

        List<CollectorTarget> targets = targetRepository
                .findBySourceTypeAndEnabledTrue(SourceType.REDDIT);

        log.info("Reddit collection started with {} targets, maxItems={}", targets.size(), maxItems);

        try {
            for (CollectorTarget target : targets) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Reddit collection interrupted");
                    break;
                }
                if (itemsCollected >= maxItems) break;

                String after = null;
                for (int page = 0; page < MAX_PAGES && itemsCollected < maxItems; page++) {
                    RedditListingResponse response = fetchListing(target, after);
                    if (response == null || response.getData() == null
                            || response.getData().getChildren() == null
                            || response.getData().getChildren().isEmpty()) {
                        break;
                    }

                    for (RedditListingResponse.RedditChild child : response.getData().getChildren()) {
                        if (itemsCollected >= maxItems) break;

                        RedditListingResponse.RedditPost post = child.getData();
                        if (post == null) continue;

                        // Filter: score > MIN_SCORE and selftext length > MIN_SELFTEXT_LENGTH
                        if (post.getScore() < MIN_SCORE) continue;
                        String selftext = post.getSelftext() != null ? post.getSelftext() : "";
                        if (selftext.length() < MIN_SELFTEXT_LENGTH) continue;

                        String sourceId = post.getId();
                        if (signalRepository.existsBySourceTypeAndSourceId(SourceType.REDDIT, sourceId)) {
                            duplicatesSkipped++;
                            continue;
                        }

                        String cleanText = Jsoup.parse(selftext).text();
                        String rawJson = buildRawJson(post, cleanText);

                        CollectorSignal signal = CollectorSignal.builder()
                                .sourceType(SourceType.REDDIT)
                                .sourceId(sourceId)
                                .rawText(rawJson)
                                .build();
                        signalRepository.save(signal);
                        itemsCollected++;
                    }

                    after = response.getData().getAfter();
                    lastCursor = after;
                    if (after == null) break;

                    // Rate limit delay
                    try { Thread.sleep(REQUEST_DELAY_MS); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("Reddit collection completed: {} items, {} duplicates skipped", itemsCollected, duplicatesSkipped);

            return CollectionResult.builder()
                    .sourceType(SourceType.REDDIT)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .lastCursor(lastCursor)
                    .duration(Duration.between(start, Instant.now()))
                    .build();

        } catch (Exception e) {
            log.error("Reddit collection failed: {}", e.getMessage(), e);
            return CollectionResult.builder()
                    .sourceType(SourceType.REDDIT)
                    .status(CollectorStatus.FAILED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .lastCursor(lastCursor)
                    .duration(Duration.between(start, Instant.now()))
                    .error(e.getMessage())
                    .build();
        }
    }

    private RedditListingResponse fetchListing(CollectorTarget target, String after) {
        int retries = 2;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                String path;
                String query;

                if ("SUBREDDIT".equals(target.getTargetType())) {
                    path = "/r/" + target.getTargetValue() + "/hot.json";
                    query = null;
                } else {
                    path = "/search.json";
                    query = target.getTargetValue();
                }

                final String finalQuery = query;
                final String finalAfter = after;

                return webClient.get()
                        .uri(uriBuilder -> {
                            var builder = uriBuilder.path(path)
                                    .queryParam("limit", LIMIT)
                                    .queryParam("t", "year")
                                    .queryParam("raw_json", 1);
                            if (finalQuery != null) {
                                builder.queryParam("q", finalQuery)
                                        .queryParam("sort", "relevance");
                            }
                            if (finalAfter != null) {
                                builder.queryParam("after", finalAfter);
                            }
                            return builder.build();
                        })
                        .retrieve()
                        .bodyToMono(RedditListingResponse.class)
                        .block(Duration.ofSeconds(30));
            } catch (Exception e) {
                log.warn("Reddit API request failed (attempt {}/{}): {}", attempt + 1, retries + 1, e.getMessage());
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

    private String buildRawJson(RedditListingResponse.RedditPost post, String cleanText) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", post.getTitle());
            data.put("selftext", cleanText);
            data.put("score", post.getScore());
            data.put("subreddit", post.getSubreddit());
            data.put("url", "https://www.reddit.com" + post.getPermalink());
            data.put("author", post.getAuthor());
            data.put("num_comments", post.getNumComments());
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize Reddit post: {}", e.getMessage());
            return "{}";
        }
    }
}
