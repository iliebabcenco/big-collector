package org.big.bigcollector.controller;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.collector.CollectionResult;
import org.big.bigcollector.collector.SourceCollector;
import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.repository.CollectorConfigRepository;
import org.big.bigcollector.repository.CollectorRunLogRepository;
import org.big.bigcollector.entity.CollectorRunLog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

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

@RestController
@Slf4j
public class CollectorRestController {

    private final CollectorConfigRepository configRepository;
    private final CollectorRunLogRepository runLogRepository;
    private final Map<SourceType, SourceCollector> collectors;
    private final ConcurrentHashMap<SourceType, Future<?>> runningCollections = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CollectorRestController(CollectorConfigRepository configRepository,
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

    @PostMapping("/collect/{sourceType}")
    public ResponseEntity<Map<String, Object>> startCollection(@PathVariable SourceType sourceType) {
        SourceCollector collector = collectors.get(sourceType);
        if (collector == null) {
            return ResponseEntity.notFound().build();
        }

        Optional<CollectorConfig> configOpt = configRepository.findBySourceType(sourceType);
        if (configOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CollectorConfig config = configOpt.get();
        if (config.getStatus() == CollectorStatus.RUNNING) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Collection already running for " + sourceType,
                "sourceType", sourceType.name()
            ));
        }

        config.setStatus(CollectorStatus.RUNNING);
        config.setLastError(null);
        configRepository.save(config);

        Future<?> future = executor.submit(() -> runCollection(collector, config));
        runningCollections.put(sourceType, future);

        return ResponseEntity.accepted().body(Map.of(
            "message", "Collection started for " + sourceType,
            "sourceType", sourceType.name(),
            "status", CollectorStatus.RUNNING.name()
        ));
    }

    @PostMapping("/stop/{sourceType}")
    public ResponseEntity<Map<String, Object>> stopCollection(@PathVariable SourceType sourceType) {
        Future<?> future = runningCollections.get(sourceType);
        if (future == null || future.isDone()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No running collection for " + sourceType,
                "sourceType", sourceType.name()
            ));
        }

        future.cancel(true);
        runningCollections.remove(sourceType);

        configRepository.findBySourceType(sourceType).ifPresent(config -> {
            config.setStatus(CollectorStatus.IDLE);
            config.setLastError("Stopped by user");
            configRepository.save(config);
        });

        return ResponseEntity.ok(Map.of(
            "message", "Stop signal sent for " + sourceType,
            "sourceType", sourceType.name()
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<List<Map<String, Object>>> getAllStatuses() {
        List<CollectorConfig> configs = configRepository.findAll();
        List<Map<String, Object>> statuses = configs.stream()
                .map(this::toStatusMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(statuses);
    }

    @GetMapping("/status/{sourceType}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable SourceType sourceType) {
        return configRepository.findBySourceType(sourceType)
                .map(config -> ResponseEntity.ok(toStatusMap(config)))
                .orElse(ResponseEntity.notFound().build());
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
                    .durationMs(java.time.Duration.between(startedAt, Instant.now()).toMillis())
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
