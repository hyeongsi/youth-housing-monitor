package kr.seoulhousing.alerts;

import android.content.Context;
import android.content.SharedPreferences;

final class Session {
    private final SharedPreferences prefs;
    Session(Context context) { prefs=context.getSharedPreferences("housing-alerts",Context.MODE_PRIVATE); }
    String deviceId() { return prefs.getString("deviceId",null); } String token() { return prefs.getString("token",null); }
    boolean paired() { return deviceId()!=null&&token()!=null; }
    void save(String id,String token) { prefs.edit().putString("deviceId",id).putString("token",token).apply(); }
}
