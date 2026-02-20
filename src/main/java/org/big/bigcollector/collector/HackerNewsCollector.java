package org.big.bigcollector.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.dto.hn.HNSearchResponse;
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
public class HackerNewsCollector implements SourceCollector {

    private static final int MAX_PAGES = 5;
    private static final int HITS_PER_PAGE = 50;

    private final CollectorTargetRepository targetRepository;
    private final CollectorSignalRepository signalRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public HackerNewsCollector(CollectorTargetRepository targetRepository,
                               CollectorSignalRepository signalRepository,
                               @Qualifier("hnWebClient") WebClient webClient,
                               ObjectMapper objectMapper) {
        this.targetRepository = targetRepository;
        this.signalRepository = signalRepository;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.HACKER_NEWS;
    }

    @Override
    public CollectionResult collect(CollectorConfig config) {
        Instant start = Instant.now();
        int itemsCollected = 0;
        int duplicatesSkipped = 0;
        String lastCursor = config.getLastCursor();
        int maxItems = config.getMaxItems() != null ? config.getMaxItems() : 100;

        List<CollectorTarget> targets = targetRepository
                .findBySourceTypeAndEnabledTrue(SourceType.HACKER_NEWS);

        log.info("HN collection started with {} targets, maxItems={}", targets.size(), maxItems);

        try {
            for (CollectorTarget target : targets) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("HN collection interrupted");
                    break;
                }
                if (itemsCollected >= maxItems) {
                    log.info("HN reached max items limit: {}", maxItems);
                    break;
                }

                String keyword = target.getTargetValue();
                String tags = "KEYWORD".equals(target.getTargetType()) ? "comment" : "ask_hn";
                String numericFilters = "KEYWORD".equals(target.getTargetType())
                        ? "points>2" : "points>10";

                log.debug("HN fetching target: type={}, value={}", target.getTargetType(), keyword);

                for (int page = 0; page < MAX_PAGES && itemsCollected < maxItems; page++) {
                    HNSearchResponse response = fetchPage(keyword, tags, numericFilters, page);
                    if (response == null || response.getHits() == null || response.getHits().isEmpty()) {
                        break;
                    }

                    for (HNSearchResponse.HNHit hit : response.getHits()) {
                        if (itemsCollected >= maxItems) break;

                        String text = hit.getCommentText() != null ? hit.getCommentText() : "";
                        if (text.isBlank() && (hit.getTitle() == null || hit.getTitle().isBlank())) {
                            continue;
                        }

                        String sourceId = hit.getObjectId();
                        if (signalRepository.existsBySourceTypeAndSourceId(SourceType.HACKER_NEWS, sourceId)) {
                            duplicatesSkipped++;
                            continue;
                        }

                        String cleanText = Jsoup.parse(text).text();
                        String rawJson = buildRawJson(hit, cleanText);

                        CollectorSignal signal = CollectorSignal.builder()
                                .sourceType(SourceType.HACKER_NEWS)
                                .sourceId(sourceId)
                                .rawText(rawJson)
                                .build();
                        signalRepository.save(signal);
                        itemsCollected++;
                    }

                    lastCursor = String.valueOf(page + 1);

                    if (page + 1 >= response.getNbPages()) break;
                }
            }

            log.info("HN collection completed: {} items, {} duplicates skipped", itemsCollected, duplicatesSkipped);

            return CollectionResult.builder()
                    .sourceType(SourceType.HACKER_NEWS)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .lastCursor(lastCursor)
                    .duration(Duration.between(start, Instant.now()))
                    .build();

        } catch (Exception e) {
            log.error("HN collection failed: {}", e.getMessage(), e);
            return CollectionResult.builder()
                    .sourceType(SourceType.HACKER_NEWS)
                    .status(CollectorStatus.FAILED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .lastCursor(lastCursor)
                    .duration(Duration.between(start, Instant.now()))
                    .error(e.getMessage())
                    .build();
        }
    }

    private HNSearchResponse fetchPage(String query, String tags, String numericFilters, int page) {
        int retries = 2;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/api/v1/search")
                                .queryParam("query", query)
                                .queryParam("tags", tags)
                                .queryParam("numericFilters", numericFilters)
                                .queryParam("hitsPerPage", HITS_PER_PAGE)
                                .queryParam("page", page)
                                .build())
                        .retrieve()
                        .bodyToMono(HNSearchResponse.class)
                        .block(Duration.ofSeconds(30));
            } catch (Exception e) {
                log.warn("HN API request failed (attempt {}/{}): {}", attempt + 1, retries + 1, e.getMessage());
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

    private String buildRawJson(HNSearchResponse.HNHit hit, String cleanText) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", hit.getTitle() != null ? hit.getTitle() :
                    (hit.getStoryTitle() != null ? hit.getStoryTitle() : ""));
            data.put("text", cleanText);
            data.put("points", hit.getPoints());
            data.put("url", hit.getUrl() != null ? hit.getUrl() :
                    "https://news.ycombinator.com/item?id=" + hit.getObjectId());
            data.put("author", hit.getAuthor());
            data.put("source_url", "https://news.ycombinator.com/item?id=" + hit.getObjectId());
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize HN hit: {}", e.getMessage());
            return "{}";
        }
    }
}
