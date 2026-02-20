package org.big.bigcollector.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
class GitHubIssueCollectorTest {

    private static final String EMPTY_RESPONSE = """
            {"total_count": 0, "incomplete_results": false, "items": []}
            """;

    @Mock
    private CollectorTargetRepository targetRepository;

    @Mock
    private CollectorSignalRepository signalRepository;

    private MockWebServer mockWebServer;
    private GitHubIssueCollector collector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .defaultHeader("Authorization", "Bearer test-token")
                .build();

        collector = new GitHubIssueCollector(targetRepository, signalRepository, webClient, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void getSourceType_returnsGitHub() {
        assertThat(collector.getSourceType()).isEqualTo(SourceType.GITHUB);
    }

    @Test
    void collect_successfulFetch_savesSignals() {
        String responseJson = """
                {
                    "total_count": 2,
                    "incomplete_results": false,
                    "items": [
                        {
                            "id": 100001,
                            "title": "Feature: Add dark mode support",
                            "body": "It would be great to have dark mode",
                            "html_url": "https://github.com/org/repo/issues/1",
                            "comments": 15,
                            "labels": [{"name": "enhancement"}],
                            "reactions": {"total_count": 25, "+1": 20, "-1": 0},
                            "repository_url": "https://api.github.com/repos/org/repo",
                            "user": {"login": "dev1"}
                        },
                        {
                            "id": 100002,
                            "title": "Feature: Export to PDF",
                            "body": "Need PDF export functionality",
                            "html_url": "https://github.com/org/repo/issues/2",
                            "comments": 8,
                            "labels": [{"name": "feature-request"}],
                            "reactions": {"total_count": 12, "+1": 11, "-1": 0},
                            "repository_url": "https://api.github.com/repos/org/repo",
                            "user": {"login": "dev2"}
                        }
                    ]
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.GITHUB)
                .targetType("LABEL")
                .targetValue("enhancement")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.GITHUB))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);

        // Use maxItems=2 so it stops after collecting 2 items without trying more pages
        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.GITHUB)
                .maxItems(2)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(2);
        verify(signalRepository, times(2)).save(any());
    }

    @Test
    void collect_authHeaderSent() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setBody(EMPTY_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.GITHUB)
                .targetType("TOPIC")
                .targetValue("CRM")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.GITHUB))
                .thenReturn(List.of(target));

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.GITHUB)
                .maxItems(100)
                .build();

        collector.collect(config);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token");
    }

    @Test
    void collect_duplicateSkipped() {
        String responseJson = """
                {
                    "total_count": 1,
                    "incomplete_results": false,
                    "items": [
                        {
                            "id": 999,
                            "title": "Existing issue",
                            "body": "Already collected",
                            "html_url": "https://github.com/org/repo/issues/999",
                            "comments": 5,
                            "labels": [],
                            "reactions": {"total_count": 10, "+1": 8, "-1": 0},
                            "repository_url": "https://api.github.com/repos/org/repo",
                            "user": {"login": "user"}
                        }
                    ]
                }
                """;

        // Page 1 with the duplicate, then empty page 2 to stop pagination
        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse()
                .setBody(EMPTY_RESPONSE)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.GITHUB)
                .targetType("LABEL")
                .targetValue("enhancement")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.GITHUB))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(SourceType.GITHUB, "999"))
                .thenReturn(true);

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.GITHUB)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        assertThat(result.duplicatesSkipped()).isEqualTo(1);
    }

    @Test
    void collect_extractsRepoName() {
        String responseJson = """
                {
                    "total_count": 1,
                    "incomplete_results": false,
                    "items": [
                        {
                            "id": 555,
                            "title": "Test",
                            "body": "Body text",
                            "html_url": "https://github.com/owner/name/issues/1",
                            "comments": 1,
                            "labels": [],
                            "reactions": {"total_count": 5, "+1": 4, "-1": 0},
                            "repository_url": "https://api.github.com/repos/owner/name",
                            "user": {"login": "dev"}
                        }
                    ]
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json"));

        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.GITHUB)
                .targetType("TOPIC")
                .targetValue("test")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.GITHUB))
                .thenReturn(List.of(target));
        when(signalRepository.existsBySourceTypeAndSourceId(any(), any()))
                .thenReturn(false);
        when(signalRepository.save(any())).thenAnswer(inv -> {
            var signal = inv.getArgument(0, org.big.bigcollector.entity.CollectorSignal.class);
            assertThat(signal.getRawText()).contains("owner/name");
            return signal;
        });

        // maxItems=1 so collector stops after collecting 1 item
        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.GITHUB)
                .maxItems(1)
                .build();

        collector.collect(config);

        verify(signalRepository, times(1)).save(any());
    }
}
