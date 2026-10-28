package kr.seoulhousing.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonitorRefreshCoordinatorTests {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path root;

    @Test void postStartsBackgroundJobAndRejectsDuplicateWhileRunning() throws Exception {
        ControlledMonitor monitor = new ControlledMonitor();
        MonitorRefreshCoordinator coordinator = new MonitorRefreshCoordinator(monitor, Duration.ZERO);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MonitorRefreshController(coordinator)).build();

        mvc.perform(post("/api/monitor/refresh"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        assertTrue(monitor.started.await(1, TimeUnit.SECONDS));
        mvc.perform(post("/api/monitor/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        monitor.release.countDown();
        awaitCompletion(coordinator);
        mvc.perform(get("/api/monitor/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.catalogCount").value(2386))
                .andExpect(jsonPath("$.newItemCount").value(1))
                .andExpect(jsonPath("$.checked[0].recordCount").value(1731));
    }

    @Test void partialSourceFailureCompletesWithWarnings() throws Exception {
        ControlledMonitor monitor = new ControlledMonitor();
        monitor.release.countDown();
        monitor.result = Map.of("catalogCount", 2386, "newItemCount", 1,
                "checked", List.of(Map.of("source", "LH", "recordCount", 43, "mode", "full")),
                "failures", List.of(Map.of("source", "SH", "error", "HTTP timeout")));
        MonitorRefreshCoordinator coordinator = new MonitorRefreshCoordinator(monitor, Duration.ZERO);

        assertEquals(MonitorRefreshCoordinator.StartOutcome.ACCEPTED, coordinator.requestManual().outcome());
        awaitCompletion(coordinator);
        RefreshJob job = coordinator.current();
        assertEquals(RefreshJobStatus.SUCCEEDED_WITH_WARNINGS, job.status());
        assertEquals(1, job.newItemCount());
        assertEquals("SH", job.failures().getFirst().get("source"));
    }

    @Test void manualRefreshIsRateLimitedForTenMinutesAfterCompletion() throws Exception {
        ControlledMonitor monitor = new ControlledMonitor();
        monitor.release.countDown();
        MonitorRefreshCoordinator coordinator = new MonitorRefreshCoordinator(monitor, Duration.ofMinutes(10));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MonitorRefreshController(coordinator)).build();

        assertEquals(MonitorRefreshCoordinator.StartOutcome.ACCEPTED, coordinator.requestManual().outcome());
        awaitCompletion(coordinator);
        mvc.perform(post("/api/monitor/refresh"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    private void awaitCompletion(MonitorRefreshCoordinator coordinator) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (coordinator.current().status() == RefreshJobStatus.RUNNING && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(coordinator.current().status() != RefreshJobStatus.RUNNING, "background collection did not complete");
    }

    private final class ControlledMonitor extends HousingMonitorService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private Map<String, Object> result = Map.of("catalogCount", 2386, "newItemCount", 1,
                "checked", List.of(Map.of("source", "SH", "recordCount", 1731, "mode", "full")), "failures", List.of());

        ControlledMonitor() { super(mapper, root.toString()); }

        @Override public Map<String, Object> refresh() {
            started.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return result;
        }
    }
}
