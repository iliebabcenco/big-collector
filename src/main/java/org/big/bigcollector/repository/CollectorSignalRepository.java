package org.big.bigcollector.repository;

import org.big.bigcollector.entity.CollectorSignal;
import org.big.bigcollector.entity.enums.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectorSignalRepository extends JpaRepository<CollectorSignal, Long> {

    List<CollectorSignal> findByProcessedFalseOrderByCreatedAtAsc();

    boolean existsBySourceTypeAndSourceId(SourceType sourceType, String sourceId);
}
