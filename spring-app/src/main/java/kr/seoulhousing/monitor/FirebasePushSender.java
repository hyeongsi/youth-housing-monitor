package kr.seoulhousing.monitor;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.util.Map;

@Service
@Primary
@ConditionalOnProperty(name = "housing.push.fcm.service-account-path")
public class FirebasePushSender implements PushSender {
    public FirebasePushSender(org.springframework.core.env.Environment environment) throws Exception {
        String path = environment.getRequiredProperty("housing.push.fcm.service-account-path");
        if (FirebaseApp.getApps().isEmpty()) {
            try (FileInputStream input = new FileInputStream(path)) {
                FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(input)).build());
            }
        }
    }

    @Override public PushResult send(String token, String title, String body, Map<String, String> data) {
        try {
            FirebaseMessaging.getInstance().send(Message.builder().setToken(token).setNotification(
                    com.google.firebase.messaging.Notification.builder().setTitle(title).setBody(body).build()).putAllData(data).build());
            return PushResult.success();
        } catch (FirebaseMessagingException error) {
            String code = error.getMessagingErrorCode() == null ? "" : error.getMessagingErrorCode().name();
            boolean invalid = code.equals("UNREGISTERED") || code.equals("INVALID_ARGUMENT");
            return PushResult.failed("FCM " + code, invalid);
        }
    }
}
