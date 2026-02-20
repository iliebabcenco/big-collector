package org.big.bigcollector.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.CollectorTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpworkCollectorTest {

    @Mock
    private CollectorTargetRepository targetRepository;

    @Mock
    private CollectorSignalRepository signalRepository;

    private UpworkCollector collector;

    @BeforeEach
    void setUp() {
        collector = new UpworkCollector(targetRepository, signalRepository, new ObjectMapper());
    }

    @Test
    void getSourceType_returnsUpwork() {
        assertThat(collector.getSourceType()).isEqualTo(SourceType.UPWORK);
    }

    @Test
    void extractBudget_withTwoAmounts() {
        String description = "Budget: $500 - $1,000. Looking for developer.";
        String[] result = collector.extractBudget(description);
        assertThat(result[0]).isEqualTo("500");
        assertThat(result[1]).isEqualTo("1000");
    }

    @Test
    void extractBudget_withSingleAmount() {
        String description = "Fixed price: $2,500. Need a web app.";
        String[] result = collector.extractBudget(description);
        assertThat(result[0]).isEqualTo("2500");
        assertThat(result[1]).isEmpty();
    }

    @Test
    void extractBudget_withNoAmount() {
        String description = "Looking for someone to build a tool. Hourly rate negotiable.";
        String[] result = collector.extractBudget(description);
        assertThat(result[0]).isEmpty();
        assertThat(result[1]).isEmpty();
    }

    @Test
    void extractBudget_withDecimalAmount() {
        String description = "Budget: $99.99";
        String[] result = collector.extractBudget(description);
        assertThat(result[0]).isEqualTo("99.99");
    }

    @Test
    void collect_noTargets_returnsCompleted() {
        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.UPWORK))
                .thenReturn(List.of());

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.UPWORK)
                .maxItems(100)
                .build();

        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
        verify(signalRepository, never()).save(any());
    }

    @Test
    void collect_rssFetchFails_gracefullyHandlesError() {
        // This target will cause a fetch failure since we're not mocking the HTTP call
        CollectorTarget target = CollectorTarget.builder()
                .sourceType(SourceType.UPWORK)
                .targetType("KEYWORD")
                .targetValue("nonexistent-fake-query-xyz")
                .enabled(true)
                .build();

        when(targetRepository.findBySourceTypeAndEnabledTrue(SourceType.UPWORK))
                .thenReturn(List.of(target));

        CollectorConfig config = CollectorConfig.builder()
                .sourceType(SourceType.UPWORK)
                .maxItems(100)
                .build();

        // Should not throw — Upwork collector catches per-keyword errors
        CollectionResult result = collector.collect(config);

        assertThat(result.status()).isEqualTo(CollectorStatus.COMPLETED);
        assertThat(result.itemsCollected()).isEqualTo(0);
    }
}
