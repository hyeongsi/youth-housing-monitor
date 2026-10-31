package kr.seoulhousing.alerts;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private TextView status; private EditText code; private String fcmToken;
    private final ActivityResultLauncher<String> notificationPermission=registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});
    @Override public void onCreate(Bundle state) { super.onCreate(state); setContentView(R.layout.activity_main); status=findViewById(R.id.pair_status); code=findViewById(R.id.pairing_code); requestNotifications(); FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token->{fcmToken=token; registerTokenIfPaired();});
        findViewById(R.id.pair).setOnClickListener(view->pair()); findViewById(R.id.inbox).setOnClickListener(view->startActivity(new Intent(this,InboxActivity.class))); findViewById(R.id.announcements).setOnClickListener(view->startActivity(new Intent(this,AnnouncementListActivity.class))); findViewById(R.id.conditions).setOnClickListener(view->startActivity(new Intent(this,ConditionsActivity.class))); updateStatus(); openAnnouncementFromIntent(getIntent()); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); openAnnouncementFromIntent(intent); }
    private void openAnnouncementFromIntent(Intent intent) { String id=intent.getStringExtra("announcementId"); if(id!=null&&!id.isBlank()) { intent.removeExtra("announcementId"); startActivity(new Intent(this,AnnouncementDetailActivity.class).putExtra("announcementId",id)); } }
    private void requestNotifications() { if(android.os.Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); }
    private void updateStatus() { status.setText(new Session(this).paired()?"이 기기는 서버에 연결되어 있습니다.":"서버에서 발급한 6자리 페어링 코드를 입력하세요."); }
    private void pair() { try { JSONObject request=new JSONObject().put("code",code.getText().toString()).put("deviceName",android.os.Build.MODEL).put("fcmToken",fcmToken); ApiClient.request(this,"POST","pair",request,(value,error)->runOnUiThread(()-> { if(error!=null) { status.setText("연결 실패: "+error.getMessage()); return; } new Session(this).save(value.optString("deviceId"),value.optString("accessToken")); updateStatus(); })); } catch(Exception error) { status.setText("코드를 확인하세요."); } }
    private void registerTokenIfPaired() { if(fcmToken==null||!new Session(this).paired()) return; try { ApiClient.request(this,"PUT","device/token",new JSONObject().put("fcmToken",fcmToken),(value,error)->{}); } catch(Exception ignored) {} }
}
