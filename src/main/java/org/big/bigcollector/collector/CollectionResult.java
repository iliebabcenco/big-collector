package org.big.bigcollector.collector;

import lombok.Builder;
import org.big.bigcollector.entity.enums.CollectorStatus;
import org.big.bigcollector.entity.enums.SourceType;

import java.time.Duration;

@Builder
public record CollectionResult(
    SourceType sourceType,
    CollectorStatus status,
    int itemsCollected,
    int newProblems,
    int duplicatesSkipped,
    String lastCursor,
    Duration duration,
    String error
) {}
