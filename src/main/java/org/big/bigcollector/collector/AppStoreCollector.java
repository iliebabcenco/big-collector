package org.big.bigcollector.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.dto.appstore.AppStoreRssResponse;
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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class AppStoreCollector implements SourceCollector {

    private static final int MIN_REVIEW_LENGTH = 50;
    private static final int MAX_RATING = 3;

    // Default popular apps per category for review mining
    private static final Map<String, List<String>> CATEGORY_APP_IDS = Map.of(
            "Productivity", List.of("1274495053", "904280696", "1150188240"),  // Things, Todoist, Notion
            "Business", List.of("507874739", "1176895641", "883919818"),       // Slack, monday.com, Trello
            "Finance", List.of("349179070", "1209657334", "310583154"),        // QuickBooks, Robinhood, Mint
            "Education", List.of("906237743", "1247608645", "568903335"),      // Duolingo, Kahoot, Google Classroom
            "Health & Fitness", List.of("1069348216", "1089047252", "1059232953") // MyFitnessPal, Headspace, Noom
    );

    private final CollectorTargetRepository targetRepository;
    private final CollectorSignalRepository signalRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public AppStoreCollector(CollectorTargetRepository targetRepository,
                             CollectorSignalRepository signalRepository,
                             @Qualifier("appleRssWebClient") WebClient webClient,
                             ObjectMapper objectMapper) {
        this.targetRepository = targetRepository;
        this.signalRepository = signalRepository;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.APP_STORE;
    }

    @Override
    public CollectionResult collect(CollectorConfig config) {
        Instant start = Instant.now();
        int itemsCollected = 0;
        int duplicatesSkipped = 0;
        int maxItems = config.getMaxItems() != null ? config.getMaxItems() : 100;

        List<CollectorTarget> targets = targetRepository
                .findBySourceTypeAndEnabledTrue(SourceType.APP_STORE);

        log.info("AppStore collection started with {} targets, maxItems={}", targets.size(), maxItems);

        try {
            for (CollectorTarget target : targets) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("AppStore collection interrupted");
                    break;
                }
                if (itemsCollected >= maxItems) {
                    log.info("AppStore reached max items limit: {}", maxItems);
                    break;
                }

                List<String> appIds = resolveAppIds(target);
                log.debug("AppStore target: type={}, value={}, appIds={}", target.getTargetType(), target.getTargetValue(), appIds.size());

                for (String appId : appIds) {
                    if (itemsCollected >= maxItems) break;

                    AppStoreRssResponse response = fetchReviews(appId);
                    if (response == null || response.getFeed() == null || response.getFeed().getEntry() == null) {
                        log.debug("AppStore no reviews for appId={}", appId);
                        continue;
                    }

                    for (AppStoreRssResponse.AppStoreEntry entry : response.getFeed().getEntry()) {
                        if (itemsCollected >= maxItems) break;

                        // Filter by rating <= 3
                        int rating = parseRating(entry);
                        if (rating > MAX_RATING) {
                            continue;
                        }

                        // Filter by review length > 50
                        String content = entry.getContent() != null ? entry.getContent().getLabel() : "";
                        if (content == null || content.length() <= MIN_REVIEW_LENGTH) {
                            continue;
                        }

                        // Extract review ID for dedup
                        String reviewId = extractReviewId(entry);
                        String sourceId = appId + "_" + reviewId;

                        if (signalRepository.existsBySourceTypeAndSourceId(SourceType.APP_STORE, sourceId)) {
                            duplicatesSkipped++;
                            continue;
                        }

                        String rawJson = buildRawJson(entry, appId);

                        CollectorSignal signal = CollectorSignal.builder()
                                .sourceType(SourceType.APP_STORE)
                                .sourceId(sourceId)
                                .rawText(rawJson)
                                .build();
                        signalRepository.save(signal);
                        itemsCollected++;
                    }
                }
            }

            log.info("AppStore collection completed: {} items, {} duplicates skipped", itemsCollected, duplicatesSkipped);

            return CollectionResult.builder()
                    .sourceType(SourceType.APP_STORE)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .duration(Duration.between(start, Instant.now()))
                    .build();

        } catch (Exception e) {
            log.error("AppStore collection failed: {}", e.getMessage(), e);
            return CollectionResult.builder()
                    .sourceType(SourceType.APP_STORE)
                    .status(CollectorStatus.FAILED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .duration(Duration.between(start, Instant.now()))
                    .error(e.getMessage())
                    .build();
        }
    }

    private List<String> resolveAppIds(CollectorTarget target) {
        if ("APP_ID".equals(target.getTargetType())) {
            return List.of(target.getTargetValue());
        }
        // CATEGORY target - resolve to known app IDs
        return CATEGORY_APP_IDS.getOrDefault(target.getTargetValue(), List.of());
    }

    private AppStoreRssResponse fetchReviews(String appId) {
        int retries = 2;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return webClient.get()
                        .uri("/rss/customerreviews/id={appId}/sortBy=mostRecent/json", appId)
                        .retrieve()
                        .bodyToMono(AppStoreRssResponse.class)
                        .block(Duration.ofSeconds(30));
            } catch (Exception e) {
                log.warn("AppStore RSS request failed for appId={} (attempt {}/{}): {}",
                        appId, attempt + 1, retries + 1, e.getMessage());
                if (attempt < retries) {
                    try { Thread.sleep(500L * (attempt + 1)); } catch (InterruptedException ie) {
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

    private int parseRating(AppStoreRssResponse.AppStoreEntry entry) {
        try {
            if (entry.getRating() != null && entry.getRating().getLabel() != null) {
                return Integer.parseInt(entry.getRating().getLabel());
            }
        } catch (NumberFormatException e) {
            log.debug("Failed to parse rating: {}", entry.getRating());
        }
        return 5; // Default to 5 (will be filtered out)
    }

    private String extractReviewId(AppStoreRssResponse.AppStoreEntry entry) {
        if (entry.getId() != null) {
            if (entry.getId().getAttributes() != null && entry.getId().getAttributes().getImId() != null) {
                return entry.getId().getAttributes().getImId();
            }
            if (entry.getId().getLabel() != null) {
                return entry.getId().getLabel();
            }
        }
        return String.valueOf(System.nanoTime());
    }

    private String buildRawJson(AppStoreRssResponse.AppStoreEntry entry, String appId) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("author", entry.getAuthor() != null && entry.getAuthor().getName() != null
                    ? entry.getAuthor().getName().getLabel() : "");
            data.put("title", entry.getTitle() != null ? entry.getTitle().getLabel() : "");
            data.put("content", entry.getContent() != null ? entry.getContent().getLabel() : "");
            data.put("rating", parseRating(entry));
            data.put("version", entry.getVersion() != null ? entry.getVersion().getLabel() : "");
            data.put("date", entry.getUpdated() != null ? entry.getUpdated().getLabel() : "");
            data.put("appId", appId);
            data.put("source_url", "https://apps.apple.com/app/id" + appId);
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize AppStore entry: {}", e.getMessage());
            return "{}";
        }
    }
}
