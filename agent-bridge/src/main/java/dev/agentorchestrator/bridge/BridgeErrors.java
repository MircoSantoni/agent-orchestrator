package dev.agentorchestrator.bridge;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
public class BridgeErrors {
    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<Map<String, Object>> remote(RestClientResponseException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", "Control Plane rejected request", "status", e.getStatusCode().value()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(503).body(Map.of("error", e.getMessage(), "status", 503));
    }
}
