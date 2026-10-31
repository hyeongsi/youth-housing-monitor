package kr.seoulhousing.alerts;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApiClient {
    interface Callback { void done(JSONObject value, Exception error); }
    private static final ExecutorService EXECUTOR=Executors.newSingleThreadExecutor();
    static void request(Context context,String method,String path,JSONObject body,Callback callback) { EXECUTOR.execute(()-> { try { callback.done(call(context,method,path,body),null); } catch(Exception error) { callback.done(null,error); } }); }
    static JSONObject call(Context context,String method,String path,JSONObject body) throws Exception {
        URL url=new URL(BuildConfig.SERVER_BASE_URL.replaceAll("/?$","/")+"api/mobile/"+path); HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setRequestProperty("Accept","application/json");
        Session session=new Session(context); if(session.paired()) { connection.setRequestProperty("X-Device-Id",session.deviceId()); connection.setRequestProperty("Authorization","Bearer "+session.token()); }
        if(body!=null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json"); try(OutputStream out=connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); } }
        InputStream in=connection.getResponseCode()<400?connection.getInputStream():connection.getErrorStream(); String response=in==null?"{}":new String(in.readAllBytes(),StandardCharsets.UTF_8); if(connection.getResponseCode()>=400) throw new IOException(response); return new JSONObject(response.isBlank()?"{}":response);
    }
}
