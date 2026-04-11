package io.policynim.mcp.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthProbeController {

    private final RuntimeReadinessService runtimeReadinessService;

    public HealthProbeController(RuntimeReadinessService runtimeReadinessService) {
        this.runtimeReadinessService = runtimeReadinessService;
    }

    @GetMapping("/healthz")
    public ResponseEntity<HealthCheckResponse> healthz() {
        HealthCheckResponse response = runtimeReadinessService.currentReadiness();
        HttpStatus status = response.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/livez")
    public ResponseEntity<Void> livez() {
        return ResponseEntity.ok().build();
    }
}
