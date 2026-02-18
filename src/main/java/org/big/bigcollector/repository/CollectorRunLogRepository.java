package org.big.bigcollector.repository;

import org.big.bigcollector.entity.CollectorRunLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectorRunLogRepository extends JpaRepository<CollectorRunLog, Long> {

    List<CollectorRunLog> findTop20ByOrderByStartedAtDesc();
}
