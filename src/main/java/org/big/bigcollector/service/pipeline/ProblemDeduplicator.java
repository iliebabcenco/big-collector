package org.big.bigcollector.service.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.big.bigcollector.dto.pipeline.ExtractedProblem;
import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.ProblemEvidence;
import org.big.bigcollector.entity.ProblemVaultEntry;
import org.big.bigcollector.repository.ProblemVaultEntryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class ProblemDeduplicator {

    private static final double DEFINITE_DUPLICATE_THRESHOLD = 0.10;
    private static final double BORDERLINE_THRESHOLD = 0.20;
    private static final double MAX_SEARCH_DISTANCE = 0.25;

    private final ProblemVaultEntryRepository vaultRepository;
    private final LlmDuplicateVerifier duplicateVerifier;

    public ProblemDeduplicator(ProblemVaultEntryRepository vaultRepository,
                                LlmDuplicateVerifier duplicateVerifier) {
        this.vaultRepository = vaultRepository;
        this.duplicateVerifier = duplicateVerifier;
    }

    /**
     * Deduplication result: either a new entry to save, or an existing entry that was updated.
     */
    public record DeduplicationResult(ProblemVaultEntry entry, boolean isNew) {}

    public DeduplicationResult deduplicate(ExtractedProblem extracted, float[] embedding, CollectorSignal signal) {
        if (embedding == null) {
            log.debug("No embedding available, inserting as new problem");
            return new DeduplicationResult(createNewEntry(extracted, embedding, signal), true);
        }

        // Search for similar problems
        String embeddingStr = embeddingToString(embedding);
        List<ProblemVaultEntry> similar = vaultRepository.findSimilarByEmbedding(
                embeddingStr, MAX_SEARCH_DISTANCE, 5);

        if (similar.isEmpty()) {
            log.debug("No similar problems found, inserting as new: {}", extracted.getTitle());
            return new DeduplicationResult(createNewEntry(extracted, embedding, signal), true);
        }

        ProblemVaultEntry closest = similar.getFirst();
        Double distance = vaultRepository.getDistanceTo(closest.getId(), embeddingStr);

        if (distance == null) {
            return new DeduplicationResult(createNewEntry(extracted, embedding, signal), true);
        }

        log.debug("Closest match: '{}' with distance {}", closest.getTitle(), distance);

        if (distance < DEFINITE_DUPLICATE_THRESHOLD) {
            // Definite duplicate — merge
            log.info("Definite duplicate found (distance={}): '{}' matches '{}'",
                    distance, extracted.getTitle(), closest.getTitle());
            return new DeduplicationResult(mergeEvidence(closest, extracted, signal), false);
        }

        if (distance < BORDERLINE_THRESHOLD) {
            // Borderline — ask LLM
            boolean isDup = duplicateVerifier.isDuplicate(
                    extracted.getTitle(), extracted.getDescription(),
                    closest.getTitle(), closest.getDescription());

            if (isDup) {
                log.info("LLM confirmed duplicate (distance={}): '{}' matches '{}'",
                        distance, extracted.getTitle(), closest.getTitle());
                return new DeduplicationResult(mergeEvidence(closest, extracted, signal), false);
            }
        }

        // New problem
        log.debug("No duplicate, inserting as new: {}", extracted.getTitle());
        return new DeduplicationResult(createNewEntry(extracted, embedding, signal), true);
    }

    private ProblemVaultEntry createNewEntry(ExtractedProblem extracted, float[] embedding, CollectorSignal signal) {
        ProblemVaultEntry entry = ProblemVaultEntry.builder()
                .title(extracted.getTitle())
                .description(extracted.getDescription())
                .problemType(extracted.getProblemType())
                .industry(extracted.getIndustry())
                .targetCustomer(extracted.getTargetCustomer())
                .sourceCount(1)
                .confidence(new BigDecimal("0.25"))
                .embedding(embedding)
                .build();

        ProblemEvidence evidence = buildEvidence(extracted, signal);
        evidence.setProblemVaultEntry(entry);
        entry.getEvidence().add(evidence);

        return entry;
    }

    private ProblemVaultEntry mergeEvidence(ProblemVaultEntry existing, ExtractedProblem extracted, CollectorSignal signal) {
        existing.setSourceCount(existing.getSourceCount() + 1);
        existing.setLastSeenAt(Instant.now());

        // Upgrade confidence based on source count
        existing.setConfidence(calculateConfidence(existing.getSourceCount()));

        ProblemEvidence evidence = buildEvidence(extracted, signal);
        evidence.setProblemVaultEntry(existing);
        existing.getEvidence().add(evidence);

        return existing;
    }

    private ProblemEvidence buildEvidence(ExtractedProblem extracted, CollectorSignal signal) {
        String quoteText = extracted.getKeyQuotes() != null && !extracted.getKeyQuotes().isEmpty()
                ? String.join(" | ", extracted.getKeyQuotes())
                : null;

        return ProblemEvidence.builder()
                .sourceType(signal.getSourceType())
                .sourceUrl(extracted.getSourceUrl())
                .rawText(signal.getRawText())
                .quoteText(quoteText)
                .collectedAt(signal.getCreatedAt())
                .build();
    }

    private BigDecimal calculateConfidence(int sourceCount) {
        if (sourceCount >= 5) return new BigDecimal("0.90"); // validated
        if (sourceCount >= 3) return new BigDecimal("0.75"); // ai_confirmed
        if (sourceCount >= 2) return new BigDecimal("0.50"); // ai_inferred
        return new BigDecimal("0.25"); // ai_predicted
    }

    private String embeddingToString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
