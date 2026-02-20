package org.big.bigcollector.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.CollectorTargetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedditCollectorTest {

    @Mock
    private CollectorTargetRepository targetRepository;

    @Mock
    private CollectorSignalRepository signalRepository;

    private MockWebServer mockWebServer;
    private RedditCollector collector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .defaultHeader("User-Agent", "BIG-Collector-Test/1.0")
                .build();

        collector = new RedditCollector(targetRepository, signalRepository, webClient, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void getSourceType_returnsReddit() {
        assertThat(collector.getSourceType()).isEqualTo(SourceType.REDDIT);
    }

    @Test
    void collect_successfulFetch_savesFilteredSignals() {
        String responseJson = """
                {
                    "data": {
                        "after": null,
                        "children": [
                            {
                                "kind": "t3",
                                "data": {
                                    "id": "abc123",
                                    "title": "I wish there was a tool for X",
                                    "selftext": "This is a long enough self text that passes the minimum length filter of fifty characters for sure.",
                                    "score": 25,
                                    "subreddit": "SaaS",
                                    "permalink": "/r/SaaS/comments/abc123/i_wish/",
                                    "author": "startup_guy",
                                    "num_comments": 12,
                                    "created_utc": 1700000000.0
                                }
                            },
                            {
                                "kind": "t3",
                                "data": {
                                    "id": "def456",
                                    "title": "Low score post",
                                    "selftext": "This has enough text but low score to be filtered out by score check.",
                                    "score": 2,
                                    "subreddit": "SaaS",
                                    "permalink": "/r/SaaS/comments/def456/low/",
                                    "author": "user2",
                                    "num_comments": 0,
                                    "created_utc": 1700000001.0
                                }
                            },
                            {
                                "kind": "t3",
                                "data": {
                                    "id": "ghi789",
                                    "title": "Short text post",
                                    "selftext": "Too short",
                                    "score": 50,
                                    "subreddit": "SaaS",
                                    "permalink": "/r/SaaS/comments/ghi789/short/",
                                    "author": "user3",
                                    "num_comments": 5,
                                    "created_utc": 1700000002.0
                                }
                            }
                        ]
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.REDDIT)
                .targetType("SUBREDDIT")
                .targetValue("SaaS")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.REDDIT))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.REDDIT)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        // Only abc123 passes both filters (score > 5 AND selftext > 50 chars)
        assertThat(result.itemsCollected()).isEqualTo(1);
        verify(signalRepository, times(1)).save(any());
    }

    @Test
    void collect_paginationWithAfterCursor() {
        String page1 = """
                {
                    "data": {
                        "after": "t3_cursor123",
                        "children": [
                            {
                                "kind": "t3",
                                "data": {
                                    "id": "page1post",
                                    "title": "First page post",
                                    "selftext": "This is a sufficiently long self text to pass the minimum character filter requirement.",
                                    "score": 10,
                                    "subreddit": "startups",
                                    "permalink": "/r/startups/comments/page1post/first/",
                                    "author": "author1",
                                    "num_comments": 3,
                                    "created_utc": 1700000000.0
                                }
                            }
                        ]
                    }
                }
                """;

        String page2 = """
                {
                    "data": {
                        "after": null,
                        "children": [
                            {
                                "kind": "t3",
                                "data": {
                                    "id": "page2post",
                                    "title": "Second page post",
                                    "selftext": "This is another sufficiently long self text to pass the minimum character filter requirement.",
                                    "score": 20,
                                    "subreddit": "startups",
                                    "permalink": "/r/startups/comments/page2post/second/",
                                    "author": "author2",
                                    "num_comments": 7,
                                    "created_utc": 1700000001.0
                                }
                            }
                        ]
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(page1)
                .addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse()
                .setBody(page2)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.REDDIT)
                .targetType("KEYWORD")
                .targetValue("I wish there was")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.REDDIT))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.REDDIT)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(2);
        verify(signalRepository, times(2)).save(any());
    }

    @Test
    void collect_duplicateSkipped() {
        String responseJson = """
                {
                    "data": {
                        "after": null,
                        "children": [
                            {
                                "kind": "t3",
                                "data": {
                                    "id": "existing",
                                    "title": "Already collected",
                                    "selftext": "This is a long enough text that passes the minimum length filter requirement for testing.",
                                    "score": 100,
                                    "subreddit": "SaaS",
                                    "permalink": "/r/SaaS/comments/existing/test/",
                                    "author": "user",
                                    "num_comments": 20,
                                    "created_utc": 1700000000.0
                                }
                            }
                        ]
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.REDDIT)
                .targetType("SUBREDDIT")
                .targetValue("SaaS")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.REDDIT))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(SourceType.REDDIT, "existing"))
                .thenReturn(true);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.REDDIT)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        assertThat(result.duplicatesSkipped()).isEqualTo(1);
        verify(signalRepository, never()).save(any());
    }
}
