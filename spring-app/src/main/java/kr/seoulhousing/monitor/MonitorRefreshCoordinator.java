package kr.seoulhousing.monitor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MonitorRefreshCoordinator {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public enum StartOutcome { ACCEPTED, ALREADY_RUNNING, RATE_LIMITED }
    public record StartResult(StartOutcome outcome, RefreshJob job) { }

    private final HousingMonitorService monitor;
    private final Duration manualCooldown;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "housing-monitor-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile RefreshJob current = RefreshJob.idle();

    @Autowired
    public MonitorRefreshCoordinator(HousingMonitorService monitor,
                                    @Value("${housing.monitor.manual-refresh-cooldown:PT10M}") Duration manualCooldown) {
        this.monitor = monitor;
        this.manualCooldown = manualCooldown;
    }

    synchronized StartResult requestManual() {
        return request("MANUAL", true);
    }

    synchronized StartResult requestScheduled() {
        return request("SCHEDULED", false);
    }

    public RefreshJob current() {
        return current;
    }

    private StartResult request(String trigger, boolean enforceCooldown) {
        if (current.status() == RefreshJobStatus.RUNNING) return new StartResult(StartOutcome.ALREADY_RUNNING, current);
        if (enforceCooldown && current.finishedAt() != null && finishedWithinCooldown(current.finishedAt())) {
            return new StartResult(StartOutcome.RATE_LIMITED, current);
        }
        RefreshJob job = new RefreshJob(UUID.randomUUID().toString(), trigger, RefreshJobStatus.RUNNING, now(), null, null, null, List.of(), List.of(), null);
        current = job;
        executor.submit(() -> run(job));
        return new StartResult(StartOutcome.ACCEPTED, job);
    }

    private void run(RefreshJob job) {
        try {
            Map<String, Object> result = monitor.refresh();
            List<Map<String, Object>> checked = list(result.get("checked"));
            List<Map<String, Object>> failures = list(result.get("failures"));
            current = new RefreshJob(job.jobId(), job.trigger(), failures.isEmpty() ? RefreshJobStatus.SUCCEEDED : RefreshJobStatus.SUCCEEDED_WITH_WARNINGS,
                    job.startedAt(), now(), number(result.get("catalogCount")), number(result.get("newItemCount")), checked, failures, null);
        } catch (Exception error) {
            current = new RefreshJob(job.jobId(), job.trigger(), RefreshJobStatus.FAILED, job.startedAt(), now(), null, null, List.of(), List.of(), safeMessage(error));
        }
    }

    private boolean finishedWithinCooldown(String finishedAt) {
        try { return LocalDateTime.parse(finishedAt, TIMESTAMP).plus(manualCooldown).isAfter(LocalDateTime.now(SEOUL)); }
        catch (RuntimeException ignored) { return false; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value) { return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of(); }
    private Integer number(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private String safeMessage(Exception error) { return error.getMessage() == null ? "수집 작업이 중단되었습니다." : error.getMessage(); }
    private String now() { return LocalDateTime.now(SEOUL).format(TIMESTAMP); }
}
