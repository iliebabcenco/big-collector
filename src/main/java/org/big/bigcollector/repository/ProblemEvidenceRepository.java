package org.big.bigcollector.repository;

import org.big.bigcollector.entity.ProblemEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProblemEvidenceRepository extends JpaRepository<ProblemEvidence, Long> {

    List<ProblemEvidence> findByProblemVaultEntryId(Long problemVaultEntryId);
}
