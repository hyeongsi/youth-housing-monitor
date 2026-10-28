package kr.seoulhousing.monitor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitor/refresh")
public class MonitorRefreshController {
    private final MonitorRefreshCoordinator coordinator;

    public MonitorRefreshController(MonitorRefreshCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping
    public ResponseEntity<RefreshJob> refresh() {
        MonitorRefreshCoordinator.StartResult result = coordinator.requestManual();
        return switch (result.outcome()) {
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(result.job());
            case ALREADY_RUNNING -> ResponseEntity.status(HttpStatus.CONFLICT).body(result.job());
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result.job());
        };
    }

    @GetMapping
    public RefreshJob status() {
        return coordinator.current();
    }
}
