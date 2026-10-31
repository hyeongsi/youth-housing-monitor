package kr.seoulhousing.monitor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledMonitorRefresh {
    private final MonitorRefreshCoordinator coordinator;

    public ScheduledMonitorRefresh(MonitorRefreshCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(cron = "${housing.monitor.schedule.cron:0 0 */3 * * *}", zone = "${housing.monitor.schedule.zone:Asia/Seoul}")
    public void refreshOnSchedule() {
        coordinator.requestScheduled();
    }
}
