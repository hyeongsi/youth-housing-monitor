package kr.seoulhousing.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Safe local-development transport: no FCM credentials or external request are required. */
@Service
@ConditionalOnMissingBean(PushSender.class)
public class LoggingPushSender implements PushSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override public PushResult send(String token, String title, String body, Map<String, String> data) {
        log.info("Push not sent because FCM is not configured: title={}, announcementId={}", title, data.get("announcementId"));
        return PushResult.success();
    }
}
