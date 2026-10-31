package kr.seoulhousing.alerts;

import android.app.*; import android.content.*; import android.os.Build; import androidx.core.app.NotificationCompat; import com.google.firebase.messaging.FirebaseMessagingService; import com.google.firebase.messaging.RemoteMessage; import org.json.JSONObject;

public class HousingMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL="housing-announcements";
    @Override public void onNewToken(String token) { try { if(new Session(this).paired()) ApiClient.request(this,"PUT","device/token",new JSONObject().put("fcmToken",token),(value,error)->{}); } catch(Exception ignored) {} }
    @Override public void onMessageReceived(RemoteMessage message) { String id=message.getData().get("announcementId"); Intent intent=new Intent(this,AnnouncementDetailActivity.class).putExtra("announcementId",id); PendingIntent pending=PendingIntent.getActivity(this,id==null?0:id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); NotificationManager manager=getSystemService(NotificationManager.class); if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(new NotificationChannel(CHANNEL,"주거 공고",NotificationManager.IMPORTANCE_DEFAULT)); String title=message.getNotification()==null?"새 주거 공고":message.getNotification().getTitle(); String body=message.getNotification()==null?"공고문에서 상세 조건을 확인하세요.":message.getNotification().getBody(); manager.notify(id==null?0:id.hashCode(),new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(pending).build()); }
}
