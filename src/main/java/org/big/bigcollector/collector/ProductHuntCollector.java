package org.big.bigcollector.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.dto.producthunt.ProductHuntGraphQLResponse;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.CollectorTargetRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class ProductHuntCollector implements SourceCollector {

    private static final int MIN_VOTES = 20;
    private static final long REQUEST_DELAY_MS = 2100;

    private static final Set<String> CONSTRUCTIVE_KEYWORDS = Set.of(
            "wish", "need", "missing", "frustrat", "annoying", "hate", "problem",
            "difficult", "hard to", "can't", "doesn't", "won't", "broken",
            "alternative", "better", "improve", "should", "lack", "pain"
    );

    private static final String POSTS_QUERY = """
            query($topic: String!, $first: Int!) {
              posts(topic: $topic, first: $first, order: VOTES) {
                edges {
                  node {
                    id
                    name
                    tagline
                    description
                    url
                    votesCount
                    comments(first: 10) {
                      edges {
                        node {
                          id
                          body
                          user {
                            name
                            username
                          }
                        }
                      }
                    }
                    topics {
                      name
                    }
                  }
                }
              }
            }
            """;

    @Value("${producthunt.token:}")
    private String token;

    private final CollectorTargetRepository targetRepository;
    private final CollectorSignalRepository signalRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ProductHuntCollector(CollectorTargetRepository targetRepository,
                                CollectorSignalRepository signalRepository,
                                @Qualifier("productHuntWebClient") WebClient webClient,
                                ObjectMapper objectMapper) {
        this.targetRepository = targetRepository;
        this.signalRepository = signalRepository;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.PRODUCT_HUNT;
    }

    @Override
    public CollectionResult collect(CollectorConfig config) {
        Instant start = Instant.now();
        int itemsCollected = 0;
        int duplicatesSkipped = 0;
        int maxItems = config.getMaxItems() != null ? config.getMaxItems() : 100;

        // Graceful skip if token not configured
        if (token == null || token.isBlank()) {
            log.warn("ProductHunt token not configured. Skipping collection.");
            return CollectionResult.builder()
                    .sourceType(SourceType.PRODUCT_HUNT)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(0)
                    .duplicatesSkipped(0)
                    .duration(Duration.between(start, Instant.now()))
                    .build();
        }

        List<CollectorTarget> targets = targetRepository
                .findBySourceTypeAndEnabledTrue(SourceType.PRODUCT_HUNT);

        log.info("ProductHunt collection started with {} targets, maxItems={}", targets.size(), maxItems);

        try {
            for (CollectorTarget target : targets) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("ProductHunt collection interrupted");
                    break;
                }
                if (itemsCollected >= maxItems) {
                    log.info("ProductHunt reached max items limit: {}", maxItems);
                    break;
                }

                String topic = target.getTargetValue();
                log.debug("ProductHunt fetching topic: {}", topic);

                ProductHuntGraphQLResponse response = fetchPosts(topic, 20);
                if (response == null || response.getData() == null
                        || response.getData().getPosts() == null
                        || response.getData().getPosts().getEdges() == null) {
                    log.debug("ProductHunt no results for topic={}", topic);
                    continue;
                }

                for (ProductHuntGraphQLResponse.PostEdge edge : response.getData().getPosts().getEdges()) {
                    if (itemsCollected >= maxItems) break;

                    ProductHuntGraphQLResponse.Post post = edge.getNode();
                    if (post == null || post.getVotesCount() < MIN_VOTES) continue;

                    // Collect constructive comments
                    if (post.getComments() != null && post.getComments().getEdges() != null) {
                        for (ProductHuntGraphQLResponse.CommentEdge commentEdge : post.getComments().getEdges()) {
                            if (itemsCollected >= maxItems) break;

                            ProductHuntGraphQLResponse.Comment comment = commentEdge.getNode();
                            if (comment == null || comment.getBody() == null) continue;

                            // Filter for constructive/negative sentiment
                            if (!hasConstructiveKeyword(comment.getBody())) continue;

                            String sourceId = "ph_" + post.getId() + "_" + comment.getId();
                            if (signalRepository.existsBySourceTypeAndSourceId(SourceType.PRODUCT_HUNT, sourceId)) {
                                duplicatesSkipped++;
                                continue;
                            }

                            String rawJson = buildCommentRawJson(post, comment, topic);

                            CollectorSignal signal = CollectorSignal.builder()
                                    .sourceType(SourceType.PRODUCT_HUNT)
                                    .sourceId(sourceId)
                                    .rawText(rawJson)
                                    .build();
                            signalRepository.save(signal);
                            itemsCollected++;
                        }
                    }
                }

                // Rate limit delay between topic requests
                try { Thread.sleep(REQUEST_DELAY_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log.info("ProductHunt collection completed: {} items, {} duplicates skipped", itemsCollected, duplicatesSkipped);

            return CollectionResult.builder()
                    .sourceType(SourceType.PRODUCT_HUNT)
                    .status(CollectorStatus.COMPLETED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .duration(Duration.between(start, Instant.now()))
                    .build();

        } catch (Exception e) {
            log.error("ProductHunt collection failed: {}", e.getMessage(), e);
            return CollectionResult.builder()
                    .sourceType(SourceType.PRODUCT_HUNT)
                    .status(CollectorStatus.FAILED)
                    .itemsCollected(itemsCollected)
                    .duplicatesSkipped(duplicatesSkipped)
                    .duration(Duration.between(start, Instant.now()))
                    .error(e.getMessage())
                    .build();
        }
    }

    private boolean hasConstructiveKeyword(String text) {
        String lower = text.toLowerCase();
        return CONSTRUCTIVE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private ProductHuntGraphQLResponse fetchPosts(String topic, int first) {
        Map<String, Object> variables = Map.of("topic", topic, "first", first);
        Map<String, Object> body = Map.of("query", POSTS_QUERY, "variables", variables);

        int retries = 2;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return webClient.post()
                        .uri("/v2/api/graphql")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(ProductHuntGraphQLResponse.class)
                        .block(Duration.ofSeconds(30));
            } catch (Exception e) {
                log.warn("ProductHunt API request failed (attempt {}/{}): {}",
                        attempt + 1, retries + 1, e.getMessage());
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

    private String buildCommentRawJson(ProductHuntGraphQLResponse.Post post,
                                        ProductHuntGraphQLResponse.Comment comment,
                                        String topic) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("product_name", post.getName());
            data.put("tagline", post.getTagline());
            data.put("votesCount", post.getVotesCount());
            data.put("comment_body", comment.getBody());
            data.put("comment_author", comment.getUser() != null ? comment.getUser().getName() : "");
            data.put("topic", topic);
            data.put("product_url", post.getUrl());
            data.put("source_url", post.getUrl());
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize ProductHunt entry: {}", e.getMessage());
            return "{}";
        }
    }
}
