package org.big.bigcollector.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.collector.CollectionResult;
import org.big.bigcollector.collector.SourceCollector;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.CollectorRunLog;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorConfigRepository;
import org.big.bigcollector.repository.CollectorRunLogRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CollectorService {

    private final CollectorConfigRepository configRepository;
    private final CollectorRunLogRepository runLogRepository;
    private final Map<SourceType, SourceCollector> collectors;
    private final ConcurrentHashMap<SourceType, Future<?>> runningCollections = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CollectorService(CollectorConfigRepository configRepository,
                            CollectorRunLogRepository runLogRepository,
                            List<SourceCollector> collectorList) {
        this.configRepository = configRepository;
        this.runLogRepository = runLogRepository;
        this.collectors = collectorList.stream()
                .collect(Collectors.toMap(SourceCollector::getSourceType, Function.identity()));
    }

    @PostConstruct
    public void resetStaleRunningStatuses() {
        List<CollectorConfig> configs = configRepository.findAll();
        for (CollectorConfig config : configs) {
            if (config.getStatus() == CollectorStatus.RUNNING) {
                log.warn("Resetting stale RUNNING status to IDLE for source: {}", config.getSourceType());
                config.setStatus(CollectorStatus.IDLE);
                config.setLastError("Reset on startup — previous run did not complete");
                configRepository.save(config);
            }
        }
    }

    public Map<String, Object> startCollection(SourceType sourceType) {
        SourceCollector collector = collectors.get(sourceType);
        if (collector == null) {
            return null;
        }

        Optional<CollectorConfig> configOpt = configRepository.findBySourceType(sourceType);
        if (configOpt.isEmpty()) {
            return null;
        }

        CollectorConfig config = configOpt.get();
        if (config.getStatus() == CollectorStatus.RUNNING) {
            return Map.of(
                "error", "Collection already running for " + sourceType,
                "sourceType", sourceType.name()
            );
        }

        config.setStatus(CollectorStatus.RUNNING);
        config.setLastError(null);
        configRepository.save(config);

        Future<?> future = executor.submit(() -> runCollection(collector, config));
        runningCollections.put(sourceType, future);

        return Map.of(
            "message", "Collection started for " + sourceType,
            "sourceType", sourceType.name(),
            "status", CollectorStatus.RUNNING.name()
        );
    }

    public Map<String, Object> stopCollection(SourceType sourceType) {
        Future<?> future = runningCollections.get(sourceType);
        if (future == null || future.isDone()) {
            return null;
        }

        future.cancel(true);
        runningCollections.remove(sourceType);

        configRepository.findBySourceType(sourceType).ifPresent(config -> {
            config.setStatus(CollectorStatus.IDLE);
            config.setLastError("Stopped by user");
            configRepository.save(config);
        });

        return Map.of(
            "message", "Stop signal sent for " + sourceType,
            "sourceType", sourceType.name()
        );
    }

    public List<Map<String, Object>> getAllStatuses() {
        return configRepository.findAll().stream()
                .map(this::toStatusMap)
                .collect(Collectors.toList());
    }

    public Optional<Map<String, Object>> getStatus(SourceType sourceType) {
        return configRepository.findBySourceType(sourceType)
                .map(this::toStatusMap);
    }

    private void runCollection(SourceCollector collector, CollectorConfig config) {
        Instant startedAt = Instant.now();
        try {
            CollectionResult result = collector.collect(config);

            config.setStatus(result.status());
            config.setLastRunAt(Instant.now());
            config.setItemsLastRun(result.itemsCollected());
            config.setLastCursor(result.lastCursor());
            config.setLastError(result.error());
            configRepository.save(config);

            CollectorRunLog runLog = CollectorRunLog.builder()
                    .sourceType(config.getSourceType())
                    .status(result.status())
                    .itemsCollected(result.itemsCollected())
                    .newProblems(result.newProblems())
                    .duplicates(result.duplicatesSkipped())
                    .durationMs(result.duration() != null ? result.duration().toMillis() : null)
                    .error(result.error())
                    .startedAt(startedAt)
                    .completedAt(Instant.now())
                    .build();
            runLogRepository.save(runLog);

        } catch (Exception e) {
            log.error("Collection failed for {}: {}", config.getSourceType(), e.getMessage(), e);
            config.setStatus(CollectorStatus.FAILED);
            config.setLastRunAt(Instant.now());
            config.setLastError(e.getMessage());
            configRepository.save(config);

            CollectorRunLog runLog = CollectorRunLog.builder()
                    .sourceType(config.getSourceType())
                    .status(CollectorStatus.FAILED)
                    .error(e.getMessage())
                    .startedAt(startedAt)
                    .completedAt(Instant.now())
                    .durationMs(Duration.between(startedAt, Instant.now()).toMillis())
                    .build();
            runLogRepository.save(runLog);
        } finally {
            runningCollections.remove(config.getSourceType());
        }
    }

    private Map<String, Object> toStatusMap(CollectorConfig config) {
        return Map.of(
            "sourceType", config.getSourceType().name(),
            "enabled", config.getEnabled(),
            "status", config.getStatus().name(),
            "lastRunAt", config.getLastRunAt() != null ? config.getLastRunAt().toString() : "",
            "itemsLastRun", config.getItemsLastRun(),
            "lastError", config.getLastError() != null ? config.getLastError() : ""
        );
    }
}
