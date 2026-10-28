package kr.seoulhousing.monitor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledMonitorRefresh {
    private final MonitorRefreshCoordinator coordinator;

    public ScheduledMonitorRefresh(MonitorRefreshCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Seoul")
    public void refreshWeekly() {
        coordinator.requestScheduled();
    }
}
