package org.big.bigcollector.service.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.config.OpenAiConfig;
import org.big.bigcollector.dto.pipeline.ExtractedProblem;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.ProblemVaultEntry;
import org.big.bigcollector.repository.CollectorSignalRepository;
import org.big.bigcollector.repository.ProblemVaultEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignalPipelineService {

    private final CollectorSignalRepository signalRepository;
    private final ProblemVaultEntryRepository vaultRepository;
    private final LlmProblemExtractor extractor;
    private final EmbeddingService embeddingService;
    private final ProblemDeduplicator deduplicator;
    private final VaultScoringService scoringService;
    private final OpenAiConfig openAiConfig;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public Map<String, Object> processUnprocessedSignals() {
        if (!openAiConfig.isConfigured()) {
            return Map.of(
                    "error", "OpenAI API key not configured",
                    "status", "SKIPPED"
            );
        }

        if (!running.compareAndSet(false, true)) {
            return Map.of(
                    "error", "Pipeline already running",
                    "status", "ALREADY_RUNNING"
            );
        }

        Instant start = Instant.now();
        int processed = 0;
        int problemsExtracted = 0;
        int newProblems = 0;
        int duplicatesMerged = 0;
        int noProblem = 0;
        int errors = 0;

        try {
            List<CollectorSignal> signals = signalRepository.findByProcessedFalseOrderByCreatedAtAsc();
            log.info("Pipeline started: {} unprocessed signals", signals.size());

            for (CollectorSignal signal : signals) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Pipeline interrupted");
                    break;
                }

                try {
                    boolean success = processSignal(signal);
                    processed++;

                    if (success) {
                        problemsExtracted++;
                        // Check if it was new or merged by looking at what processSignal did
                        // We track this via the return mechanism
                    } else {
                        noProblem++;
                    }
                } catch (Exception e) {
                    log.error("Failed to process signal {}: {}", signal.getId(), e.getMessage());
                    markSignalFailed(signal, e.getMessage());
                    errors++;
                }
            }

            Duration duration = Duration.between(start, Instant.now());
            log.info("Pipeline completed in {}: {} processed, {} problems extracted, {} errors",
                    duration, processed, problemsExtracted, errors);

            return Map.of(
                    "status", "COMPLETED",
                    "totalSignals", signals.size(),
                    "processed", processed,
                    "problemsExtracted", problemsExtracted,
                    "noProblem", noProblem,
                    "errors", errors,
                    "durationMs", duration.toMillis()
            );
        } finally {
            running.set(false);
        }
    }

    @Transactional
    boolean processSignal(CollectorSignal signal) {
        // Step 1: Extract problem from raw text
        ExtractedProblem extracted = extractor.extract(signal);

        if (!extracted.isValid()) {
            markSignalProcessed(signal);
            return false;
        }

        // Step 2: Generate embedding
        float[] embedding = embeddingService.generateEmbedding(
                extracted.getTitle(), extracted.getDescription());

        // Step 3: Deduplicate
        ProblemDeduplicator.DeduplicationResult dedupResult =
                deduplicator.deduplicate(extracted, embedding, signal);

        ProblemVaultEntry entry = dedupResult.entry();

        // Step 4: Score new problems only
        if (dedupResult.isNew()) {
            scoringService.scoreEntry(entry);
        }

        // Step 5: Save
        vaultRepository.save(entry);

        // Step 6: Mark signal as processed
        markSignalProcessed(signal);

        return true;
    }

    private void markSignalProcessed(CollectorSignal signal) {
        signal.setProcessed(true);
        signal.setProcessedAt(Instant.now());
        signalRepository.save(signal);
    }

    private void markSignalFailed(CollectorSignal signal, String errorMessage) {
        signal.setProcessed(true);
        signal.setProcessedAt(Instant.now());
        signal.setError(errorMessage);
        signalRepository.save(signal);
    }

    public boolean isRunning() {
        return running.get();
    }
}
