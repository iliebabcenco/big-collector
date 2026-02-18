package org.big.bigcollector.repository;

import org.big.bigcollector.entity.ProblemVaultEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemVaultEntryRepository extends JpaRepository<ProblemVaultEntry, Long> {
}
