package kr.seoulhousing.monitor;

import java.util.List;
import java.util.Map;

public record RefreshJob(
        String jobId,
        String trigger,
        RefreshJobStatus status,
        String startedAt,
        String finishedAt,
        Integer catalogCount,
        Integer newItemCount,
        List<Map<String, Object>> checked,
        List<Map<String, Object>> failures,
        String error
) {
    static RefreshJob idle() {
        return new RefreshJob(null, null, RefreshJobStatus.IDLE, null, null, null, null, List.of(), List.of(), null);
    }
}
