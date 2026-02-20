package org.big.bigcollector.controller;

import lombok.RequiredArgsConstructor;
import org.big.bigcollector.service.pipeline.SignalPipelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/pipeline")
@RequiredArgsConstructor
public class PipelineRestController {

    private final SignalPipelineService pipelineService;

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processSignals() {
        Map<String, Object> result = pipelineService.processUnprocessedSignals();

        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "running", pipelineService.isRunning()
        ));
    }
}
