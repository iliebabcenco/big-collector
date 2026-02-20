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
class AppStoreCollectorTest {

    @Mock
    private CollectorTargetRepository targetRepository;

    @Mock
    private CollectorSignalRepository signalRepository;

    private MockWebServer mockWebServer;
    private AppStoreCollector collector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        collector = new AppStoreCollector(targetRepository, signalRepository, webClient, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void getSourceType_returnsAppStore() {
        assertThat(collector.getSourceType()).isEqualTo(SourceType.APP_STORE);
    }

    @Test
    void collect_successfulFetch_savesFilteredReviews() {
        // 3 reviews: one with rating 2 (long enough), one with rating 1 (long enough), one with rating 4 (filtered)
        String responseJson = """
                {
                    "feed": {
                        "entry": [
                            {
                                "author": {"name": {"label": "user1"}, "uri": {"label": "http://example.com"}},
                                "im:rating": {"label": "2"},
                                "im:version": {"label": "3.0"},
                                "id": {"label": "123456", "attributes": {"im:id": "rev1"}},
                                "title": {"label": "Terrible update"},
                                "content": {"label": "This app used to be great but the latest update completely broke the workflow. I can no longer do basic tasks."},
                                "updated": {"label": "2024-01-15T10:00:00Z"}
                            },
                            {
                                "author": {"name": {"label": "user2"}, "uri": {"label": "http://example.com"}},
                                "im:rating": {"label": "1"},
                                "im:version": {"label": "3.0"},
                                "id": {"label": "123457", "attributes": {"im:id": "rev2"}},
                                "title": {"label": "Crashes constantly"},
                                "content": {"label": "The app crashes every time I try to open it. This has been going on for weeks and no fix in sight. Very disappointed."},
                                "updated": {"label": "2024-01-16T10:00:00Z"}
                            },
                            {
                                "author": {"name": {"label": "user3"}, "uri": {"label": "http://example.com"}},
                                "im:rating": {"label": "4"},
                                "im:version": {"label": "3.0"},
                                "id": {"label": "123458", "attributes": {"im:id": "rev3"}},
                                "title": {"label": "Pretty good"},
                                "content": {"label": "The app works well for most things. Could use some improvements but overall I'm happy with it and use it daily."},
                                "updated": {"label": "2024-01-17T10:00:00Z"}
                            }
                        ]
                    }
                }
                """;

        // Enqueue responses for each app in the Productivity category (3 apps)
        mockWebServer.enqueue(new MockResponse().setBody(responseJson).addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse().setBody(responseJson).addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse().setBody(responseJson).addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.APP_STORE)
                .targetType("CATEGORY")
                .targetValue("Productivity")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.APP_STORE))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.APP_STORE)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        // 2 reviews per app (rating <= 3 and length > 50), 3 apps = 6
        assertThat(result.itemsCollected()).isEqualTo(6);
        assertThat(result.duplicatesSkipped()).isEqualTo(0);
        verify(signalRepository, times(6)).save(any());
    }

    @Test
    void collect_highRatingReviews_filtered() {
        String responseJson = """
                {
                    "feed": {
                        "entry": [
                            {
                                "author": {"name": {"label": "happy_user"}},
                                "im:rating": {"label": "5"},
                                "im:version": {"label": "2.0"},
                                "id": {"label": "999", "attributes": {"im:id": "rev_high"}},
                                "title": {"label": "Love it"},
                                "content": {"label": "This is the best app I have ever used. It works perfectly and I love all the features. Highly recommended to everyone."},
                                "updated": {"label": "2024-01-15T10:00:00Z"}
                            }
                        ]
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse().setBody(responseJson).addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.APP_STORE)
                .targetType("APP_ID")
                .targetValue("123456789")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.APP_STORE))
                .thenReturn(List.of(target));

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.APP_STORE)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_shortReviews_filtered() {
        String responseJson = """
                {
                    "feed": {
                        "entry": [
                            {
                                "author": {"name": {"label": "brief_user"}},
                                "im:rating": {"label": "1"},
                                "im:version": {"label": "2.0"},
                                "id": {"label": "888", "attributes": {"im:id": "rev_short"}},
                                "title": {"label": "Bad"},
                                "content": {"label": "App is terrible. Worst ever."},
                                "updated": {"label": "2024-01-15T10:00:00Z"}
                            }
                        ]
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse().setBody(responseJson).addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.APP_STORE)
                .targetType("APP_ID")
                .targetValue("123456789")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.APP_STORE))
                .thenReturn(List.of(target));

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.APP_STORE)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_duplicateSignals_skipped() {
        String responseJson = """
                {
                    "feed": {
                        "entry": [
                            {
                                "author": {"name": {"label": "user1"}},
                                "im:rating": {"label": "2"},
                                "im:version": {"label": "3.0"},
                                "id": {"label": "existing", "attributes": {"im:id": "existing_rev"}},
                                "title": {"label": "Bad update"},
                                "content": {"label": "This update is terrible and has broken everything that used to work perfectly fine before."},
                                "updated": {"label": "2024-01-15T10:00:00Z"}
                            }
                        ]
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse().setBody(responseJson).addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.APP_STORE)
                .targetType("APP_ID")
                .targetValue("123456789")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.APP_STORE))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(SourceType.APP_STORE, "123456789_existing_rev"))
                .thenReturn(true);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.APP_STORE)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        assertThat(result.duplicatesSkipped()).isEqualTo(1);
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_emptyFeed_returnsZeroItems() {
        String responseJson = """
                {
                    "feed": {
                        "entry": []
                    }
                }
                """;

        mockWebServer.enqueue(new MockResponse().setBody(responseJson).addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.APP_STORE)
                .targetType("APP_ID")
                .targetValue("123456789")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.APP_STORE))
                .thenReturn(List.of(target));

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.APP_STORE)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        verify(signalRepository, never()).save(any());
    }
}
