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
class HackerNewsCollectorTest {

    @Mock
    private CollectorTargetRepository targetRepository;

    @Mock
    private CollectorSignalRepository signalRepository;

    private MockWebServer mockWebServer;
    private HackerNewsCollector collector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        collector = new HackerNewsCollector(targetRepository, signalRepository, webClient, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void getSourceType_returnsHackerNews() {
        assertThat(collector.getSourceType()).isEqualTo(SourceType.HACKER_NEWS);
    }

    @Test
    void collect_successfulFetch_savesSignals() {
        String responseJson = """
                {
                    "hits": [
                        {
                            "objectID": "12345",
                            "title": "Ask HN: Why no simple PM tool?",
                            "comment_text": "I <b>wish</b> there was a simpler tool",
                            "author": "testuser",
                            "points": 89,
                            "url": null
                        },
                        {
                            "objectID": "12346",
                            "title": null,
                            "comment_text": "Someone should build this",
                            "story_title": "Show HN: My Project",
                            "author": "user2",
                            "points": 15,
                            "url": null
                        }
                    ],
                    "page": 0,
                    "nbPages": 1,
                    "nbHits": 2
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .targetType("KEYWORD")
                .targetValue("I wish there was")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.HACKER_NEWS))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(2);
        assertThat(result.duplicatesSkipped()).isEqualTo(0);
        verify(signalRepository, times(2)).save(any());
    }

    @Test
    void collect_emptyResults_returnsZeroItems() {
        String responseJson = """
                {
                    "hits": [],
                    "page": 0,
                    "nbPages": 0,
                    "nbHits": 0
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .targetType("KEYWORD")
                .targetValue("nonexistent query")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.HACKER_NEWS))
                .thenReturn(List.of(target));

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.HACKER_NEWS)
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
                    "hits": [
                        {
                            "objectID": "existing-id",
                            "title": "Existing post",
                            "comment_text": "Already collected text",
                            "author": "user",
                            "points": 10,
                            "url": null
                        }
                    ],
                    "page": 0,
                    "nbPages": 1,
                    "nbHits": 1
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .targetType("KEYWORD")
                .targetValue("test")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.HACKER_NEWS))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(SourceType.HACKER_NEWS, "existing-id"))
                .thenReturn(true);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        assertThat(result.duplicatesSkipped()).isEqualTo(1);
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_htmlStrippedFromText() {
        String responseJson = """
                {
                    "hits": [
                        {
                            "objectID": "html-test",
                            "title": "HTML test",
                            "comment_text": "This has <b>bold</b> and <a href=\\"http://x.com\\">links</a>",
                            "author": "user",
                            "points": 5,
                            "url": null
                        }
                    ],
                    "page": 0,
                    "nbPages": 1,
                    "nbHits": 1
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .targetType("KEYWORD")
                .targetValue("test")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.HACKER_NEWS))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);
        when(signalRepository.save(any())).thenAnswer(inv -> {
            var signal = inv.getArgument(0, org.big.bigcollector.entity.CollectorSignal.class);
            // Verify HTML tags are stripped from the raw JSON text field
            assertThat(signal.getRawText()).doesNotContain("<b>", "</b>", "<a ");
            assertThat(signal.getRawText()).contains("bold");
            assertThat(signal.getRawText()).contains("links");
            return signal;
        });

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(1);
    }

    @Test
    void collect_serverError_retriesThenFails() {
        // Enqueue 3 error responses (1 initial + 2 retries)
        for (int i = 0; i < 3; i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        }

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .targetType("KEYWORD")
                .targetValue("test")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.HACKER_NEWS))
                .thenReturn(List.of(target));

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.HACKER_NEWS)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.FAILED);
        assertThat(result.error()).isNotNull();
    }
}
