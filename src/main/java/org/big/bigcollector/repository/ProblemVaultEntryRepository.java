package org.big.bigcollector.repository;

import org.big.bigcollector.entity.ProblemVaultEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProblemVaultEntryRepository extends JpaRepository<ProblemVaultEntry, Long> {

    /**
     * Find similar problems using pgvector cosine distance.
     * Returns entries where cosine distance < maxDistance, ordered by closest match.
     * Cosine distance = 1 - cosine_similarity, so lower = more similar.
     */
    @Query(value = """
            SELECT pv.* FROM problem_vault pv
            WHERE pv.embedding IS NOT NULL
            AND (pv.embedding <=> cast(:embedding as vector)) < :maxDistance
            ORDER BY (pv.embedding <=> cast(:embedding as vector)) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<ProblemVaultEntry> findSimilarByEmbedding(
            @Param("embedding") String embedding,
            @Param("maxDistance") double maxDistance,
            @Param("limit") int limit);

    @Query(value = """
            SELECT (pv.embedding <=> cast(:embedding as vector)) as distance
            FROM problem_vault pv
            WHERE pv.id = :id AND pv.embedding IS NOT NULL
            """, nativeQuery = true)
    Double getDistanceTo(@Param("id") Long id, @Param("embedding") String embedding);
}
