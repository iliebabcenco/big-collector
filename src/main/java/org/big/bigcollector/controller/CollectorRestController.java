package org.big.bigcollector.controller;

import lombok.RequiredArgsConstructor;
import org.big.bigcollector.entity.enums.SourceType;
import org.big.bigcollector.service.CollectorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CollectorRestController {

    private final CollectorService collectorService;

    @PostMapping("/collect/{sourceType}")
    public ResponseEntity<Map<String, Object>> startCollection(@PathVariable SourceType sourceType) {
        Map<String, Object> result = collectorService.startCollection(sourceType);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping("/stop/{sourceType}")
    public ResponseEntity<Map<String, Object>> stopCollection(@PathVariable SourceType sourceType) {
        Map<String, Object> result = collectorService.stopCollection(sourceType);
        if (result == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No running collection for " + sourceType,
                "sourceType", sourceType.name()
            ));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<List<Map<String, Object>>> getAllStatuses() {
        return ResponseEntity.ok(collectorService.getAllStatuses());
    }

    @GetMapping("/status/{sourceType}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable SourceType sourceType) {
        return collectorService.getStatus(sourceType)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
