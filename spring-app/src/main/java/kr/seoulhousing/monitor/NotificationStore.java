package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class NotificationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public NotificationStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; initialize(); }
    private void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS mobile_devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, access_token TEXT NOT NULL UNIQUE, fcm_token TEXT, active INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pairing_codes (code TEXT PRIMARY KEY, expires_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS notification_conditions (id INTEGER PRIMARY KEY CHECK(id=1), regions TEXT NOT NULL, housing_types TEXT NOT NULL, statuses TEXT NOT NULL, keywords TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS notification_events (announcement_id TEXT PRIMARY KEY, created_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS notification_deliveries (announcement_id TEXT NOT NULL, device_id TEXT NOT NULL, status TEXT NOT NULL, error TEXT, sent_at TEXT NOT NULL, PRIMARY KEY(announcement_id, device_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mobile_inbox (id INTEGER PRIMARY KEY AUTOINCREMENT, announcement_id TEXT NOT NULL, device_id TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, read_at TEXT, created_at TEXT NOT NULL, UNIQUE(announcement_id, device_id))");
        jdbc.update("INSERT OR IGNORE INTO notification_conditions (id,regions,housing_types,statuses,keywords) VALUES(1,'[]','[]','[]','[]')");
    }
    public boolean consumePairingCode(String code) { return jdbc.update("DELETE FROM pairing_codes WHERE code=? AND expires_at>?", code, Instant.now().toString()) == 1; }
    public void savePairingCode(String code, Instant expiresAt) { jdbc.update("DELETE FROM pairing_codes WHERE expires_at<=?", Instant.now().toString()); jdbc.update("INSERT OR REPLACE INTO pairing_codes(code,expires_at) VALUES (?,?)", code, expiresAt.toString()); }
    public void saveDevice(String id, String name, String accessToken, String fcmToken) { String now=Instant.now().toString(); jdbc.update("INSERT INTO mobile_devices(id,name,access_token,fcm_token,active,created_at,updated_at) VALUES(?,?,?,?,1,?,?)", id,name,accessToken,fcmToken,now,now); }
    public boolean authenticated(String deviceId, String token) { return jdbc.queryForObject("SELECT COUNT(*) FROM mobile_devices WHERE id=? AND access_token=? AND active=1", Integer.class, deviceId, token) > 0; }
    public void updateToken(String deviceId, String fcmToken) { jdbc.update("UPDATE mobile_devices SET fcm_token=?,active=1,updated_at=? WHERE id=?", fcmToken,Instant.now().toString(),deviceId); }
    public void deactivate(String deviceId) { jdbc.update("UPDATE mobile_devices SET active=0, fcm_token=NULL, updated_at=? WHERE id=?",Instant.now().toString(),deviceId); }
    public List<Map<String, Object>> activeDevices() { return jdbc.queryForList("SELECT id,name,fcm_token FROM mobile_devices WHERE active=1 AND fcm_token IS NOT NULL AND fcm_token<>''"); }
    public boolean eventExists(String announcementId) { return jdbc.queryForObject("SELECT COUNT(*) FROM notification_events WHERE announcement_id=?", Integer.class, announcementId) > 0; }
    public void event(String announcementId) { jdbc.update("INSERT OR IGNORE INTO notification_events(announcement_id,created_at) VALUES(?,?)",announcementId,Instant.now().toString()); }
    public boolean reserveDelivery(String announcementId, String deviceId) { return jdbc.update("INSERT OR IGNORE INTO notification_deliveries(announcement_id,device_id,status,sent_at) VALUES(?,?,?,?)",announcementId,deviceId,"PENDING",Instant.now().toString()) == 1; }
    public void finishDelivery(String announcementId, String deviceId, String status, String error) { jdbc.update("UPDATE notification_deliveries SET status=?,error=?,sent_at=? WHERE announcement_id=? AND device_id=?",status,error,Instant.now().toString(),announcementId,deviceId); }
    public void inbox(String announcementId, String deviceId, String title, String body) { jdbc.update("INSERT OR IGNORE INTO mobile_inbox(announcement_id,device_id,title,body,created_at) VALUES(?,?,?,?,?)",announcementId,deviceId,title,body,Instant.now().toString()); }
    public List<Map<String,Object>> inbox(String deviceId) { return jdbc.queryForList("SELECT id,announcement_id AS announcementId,title,body,created_at AS createdAt,read_at AS readAt FROM mobile_inbox WHERE device_id=? ORDER BY id DESC",deviceId); }
    public boolean markRead(String deviceId, long id) { return jdbc.update("UPDATE mobile_inbox SET read_at=? WHERE id=? AND device_id=?",Instant.now().toString(),id,deviceId)==1; }
    public Map<String,List<String>> conditions() { Map<String,Object> row=jdbc.queryForMap("SELECT regions,housing_types,statuses,keywords FROM notification_conditions WHERE id=1"); Map<String,List<String>> result=new LinkedHashMap<>(); result.put("regions", list(row.get("regions"))); result.put("housingTypes", list(row.get("housing_types"))); result.put("statuses", list(row.get("statuses"))); result.put("keywords", list(row.get("keywords"))); return result; }
    public void conditions(Map<String,List<String>> value) { jdbc.update("UPDATE notification_conditions SET regions=?,housing_types=?,statuses=?,keywords=? WHERE id=1", json(value.get("regions")),json(value.get("housingTypes")),json(value.get("statuses")),json(value.get("keywords"))); }
    private List<String> list(Object value) { try { return mapper.readValue(String.valueOf(value),new TypeReference<List<String>>(){}); } catch(Exception error) { return List.of(); } }
    private String json(List<String> value) { try { return mapper.writeValueAsString(value==null?List.of():value); } catch(Exception error) { throw new IllegalStateException(error); } }
}
