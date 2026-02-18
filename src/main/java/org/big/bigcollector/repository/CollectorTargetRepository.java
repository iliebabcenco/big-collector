package org.big.bigcollector.repository;

import org.big.bigcollector.entity.CollectorTarget;
import org.big.bigcollector.entity.enums.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectorTargetRepository extends JpaRepository<CollectorTarget, Long> {

    List<CollectorTarget> findBySourceTypeAndEnabledTrue(SourceType sourceType);
}
