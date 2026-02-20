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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductHuntCollectorTest {

    @Mock
    private CollectorTargetRepository targetRepository;

    @Mock
    private CollectorSignalRepository signalRepository;

    private MockWebServer mockWebServer;
    private ProductHuntCollector collector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        collector = new ProductHuntCollector(targetRepository, signalRepository, webClient, objectMapper);
        ReflectionTestUtils.setField(collector, "token", "test-token");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void getSourceType_returnsProductHunt() {
        assertThat(collector.getSourceType()).isEqualTo(SourceType.PRODUCT_HUNT);
    }

    @Test
    void collect_successfulFetch_savesSignals() {
        String responseJson = """
                {
                    "data": {
                        "posts": {
                            "edges": [
                                {
                                    "node": {
                                        "id": "post1",
                                        "name": "CoolTool",
                                        "tagline": "The best tool ever",
                                        "description": "A great description",
                                        "url": "https://www.producthunt.com/posts/cooltool",
                                        "votesCount": 150,
                                        "comments": {
                                            "edges": [
                                                {
                                                    "node": {
                                                        "id": "comment1",
                                                        "body": "I wish this tool had better integration. It's frustrating that it doesn't work with my workflow.",
                                                        "user": {
                                                            "name": "John Doe",
                                                            "username": "johndoe"
                                                        }
                                                    }
                                                },
                                                {
                                                    "node": {
                                                        "id": "comment2",
                                                        "body": "Great product! Love it!",
                                                        "user": {
                                                            "name": "Jane",
                                                            "username": "jane"
                                                        }
                                                    }
                                                }
                                            ]
                                        },
                                        "topics": [{"name": "SaaS"}]
                                    }
                                }
                            ]
                        }
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.PRODUCT_HUNT)
                .targetType("TOPIC")
                .targetValue("SaaS")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.PRODUCT_HUNT))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.PRODUCT_HUNT)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        // Only comment1 has constructive keywords ("wish", "frustrating"), comment2 is positive
        assertThat(result.itemsCollected()).isEqualTo(1);
        verify(signalRepository, times(1)).save(any());
    }

    @Test
    void collect_noToken_skipsGracefully() {
        ReflectionTestUtils.setField(collector, "token", "");

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.PRODUCT_HUNT)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        verify(targetRepository, never()).findBySourceTypeAndEnabledTrue(any());
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_duplicateSignals_skipped() {
        String responseJson = """
                {
                    "data": {
                        "posts": {
                            "edges": [
                                {
                                    "node": {
                                        "id": "post1",
                                        "name": "ExistingTool",
                                        "tagline": "Already collected",
                                        "url": "https://www.producthunt.com/posts/existingtool",
                                        "votesCount": 200,
                                        "comments": {
                                            "edges": [
                                                {
                                                    "node": {
                                                        "id": "existing_comment",
                                                        "body": "I wish this had better features. It's missing key functionality.",
                                                        "user": {
                                                            "name": "User",
                                                            "username": "user"
                                                        }
                                                    }
                                                }
                                            ]
                                        },
                                        "topics": [{"name": "SaaS"}]
                                    }
                                }
                            ]
                        }
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.PRODUCT_HUNT)
                .targetType("TOPIC")
                .targetValue("SaaS")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.PRODUCT_HUNT))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(SourceType.PRODUCT_HUNT, "ph_post1_existing_comment"))
                .thenReturn(true);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.PRODUCT_HUNT)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        assertThat(result.duplicatesSkipped()).isEqualTo(1);
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_emptyResults_returnsZeroItems() {
        String responseJson = """
                {
                    "data": {
                        "posts": {
                            "edges": []
                        }
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.PRODUCT_HUNT)
                .targetType("TOPIC")
                .targetValue("SaaS")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.PRODUCT_HUNT))
                .thenReturn(List.of(target));

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.PRODUCT_HUNT)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        verify(signalRepository, never()).save(any());
    }
}
