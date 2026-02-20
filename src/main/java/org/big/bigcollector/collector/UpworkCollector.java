package org.big.bigcollector.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.CollectorTargetRepository;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class UpworkCollector implements SourceCollector {

    private static final long KEYWORD_DELAY_MS = 1000;
    private static final Pattern BUDGET_PATTERN = Pattern.compile("\\$([\\d,]+(?:\\.\\d{2})?)");

    private final CollectorTargetRepository targetRepository;
    private final CollectorSignalRepository signalRepository;
    private final ObjectMapper objectMapper;

    public UpworkCollector(CollectorTargetRepository targetRepository,
                           CollectorSignalRepository signalRepository,
                           ObjectMapper objectMapper) {
        this.targetRepository = targetRepository;
        this.signalRepository = signalRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.UPWORK;
    }

    @Override
    public CollectionResult collect(CollectorConfig config) {
        Instant start = Instant.now();
        int itemsCollected = 0;
        int duplicatesSkipped = 0;
        int maxItems = config.getMaxItems() != null ? config.getMaxItems() : 100;

        List<CollectorTarget> targets = targetRepository
                .findBySourceTypeAndEnabledTrue(SourceType.UPWORK);

        log.info("Upwork collection started with {} targets, maxItems={}", targets.size(), maxItems);

        try {
            for (CollectorTarget target : targets) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Upwork collection interrupted");
                    break;
                }
                if (itemsCollected >= maxItems) break;

                String keyword = target.getTargetValue();
                log.debug("Upwork fetching RSS for keyword: {}", keyword);

                try {
                    String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
                    String feedUrl = "https://www.upwork.com/ab/feed/jobs/rss?q=" + encodedKeyword + "&sort=recency";

                    SyndFeedInput input = new SyndFeedInput();
                    SyndFeed feed = input.build(new XmlReader(URI.create(feedUrl).toURL()));

                    for (SyndEntry entry : feed.getEntries()) {
                        if (itemsCollected >= maxItems) break;

                        String link = entry.getLink();
                        String sourceId = link != null ? link : entry.getUri();
                        if (sourceId == null) continue;

                        if (signalRepository.existsBySourceTypeAndSourceId(SourceType.UPWORK, sourceId)) {
                            duplicatesSkipped++;
                            continue;
                        }

                        String description = entry.getDescription() != null
                                ? Jsoup.parse(entry.getDescription().getValue()).text() : "";
                        String pubDate = entry.getPublishedDate() != null
                                ? entry.getPublishedDate().toInstant().toString() : "";

                        String[] budgets = extractBudget(description);

                        String rawJson = buildRawJson(entry.getTitle(), description, budgets[0], budgets[1], link, pubDate);

                        CollectorSignal signal = CollectorSignal.builder()
                                .sourceType(SourceType.UPWORK)
                                .sourceId(sourceId)
                                .rawText(rawJson)
                                .build();
                        signalRepository.save(signal);
                        itemsCollected++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch Upwork RSS for keyword '{}': {}", keyword, e.getMessage());
                }

                // Delay between keyword fetches
                try { Thread.sleep(KEYWORD_DELAY_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log.info("Upwork collection completed: {} items, {} duplicates skipped", itemsCollected, duplicatesSkipped);

            return CollectionResult.builder()
                    .sourceType(SourceType.UPWORK)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .duration(Duration.between(start, Instant.now()))
                    .build();

        } catch (Exception e) {
            log.error("Upwork collection failed: {}", e.getMessage(), e);
            return CollectionResult.builder()
                    .sourceType(SourceType.UPWORK)
                    .status(CollectorStatus.FAILED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .duration(Duration.between(start, Instant.now()))
                    .error(e.getMessage())
                    .build();
        }
    }

    String[] extractBudget(String description) {
        String[] result = {"", ""};
        if (description == null || description.isBlank()) return result;

        Matcher matcher = BUDGET_PATTERN.matcher(description);
        if (matcher.find()) {
            result[0] = matcher.group(1).replace(",", "");
            if (matcher.find()) {
                result[1] = matcher.group(1).replace(",", "");
            }
        }
        return result;
    }

    private String buildRawJson(String title, String description, String budgetMin, String budgetMax,
                                String link, String pubDate) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", title != null ? title : "");
            data.put("description", description);
            data.put("budget_min", budgetMin);
            data.put("budget_max", budgetMax);
            data.put("link", link != null ? link : "");
            data.put("pubDate", pubDate);
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize Upwork item: {}", e.getMessage());
            return "{}";
        }
    }
}
