package org.big.bigcollector.repository;

import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.enums.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CollectorConfigRepository extends JpaRepository<CollectorConfig, Long> {

    Optional<CollectorConfig> findBySourceType(SourceType sourceType);
}
