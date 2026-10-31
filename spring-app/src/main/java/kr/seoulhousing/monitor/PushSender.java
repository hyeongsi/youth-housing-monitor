package kr.seoulhousing.monitor;

import java.util.Map;

public interface PushSender {
    PushResult send(String token, String title, String body, Map<String, String> data);

    record PushResult(boolean sent, boolean invalidToken, String error) {
        static PushResult success() { return new PushResult(true, false, null); }
        static PushResult failed(String error, boolean invalidToken) { return new PushResult(false, invalidToken, error); }
    }
}
