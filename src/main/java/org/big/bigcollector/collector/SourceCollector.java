package org.big.bigcollector.collector;

import org.big.bigcollector.entity.CollectorConfig;
import org.big.bigcollector.entity.enums.SourceType;

public interface SourceCollector {

    SourceType getSourceType();

    CollectionResult collect(CollectorConfig config);
}
