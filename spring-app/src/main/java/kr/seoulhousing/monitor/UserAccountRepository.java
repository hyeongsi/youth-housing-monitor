package kr.seoulhousing.monitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Repository
class UserAccountRepository {
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions;
    UserAccountRepository(JdbcTemplate jdbc,PlatformTransactionManager transactionManager,AnnouncementRepository ignored){this.jdbc=jdbc;this.transactions=new TransactionTemplate(transactionManager);initialize();}
    private void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, display_name TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, last_login_at TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_identities (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, provider TEXT NOT NULL, provider_subject TEXT NOT NULL, email TEXT, created_at TEXT NOT NULL, UNIQUE(provider,provider_subject))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_saved_announcements (user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE, display_order INTEGER NOT NULL, note TEXT, reminder_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(user_id,announcement_id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_user_saved_announcements_order ON user_saved_announcements(user_id,display_order)");
    }
    String upsertGoogle(String subject,String email,String name){
        String existing=findId(subject);String now=Instant.now().toString();
        if(existing!=null){jdbc.update("UPDATE users SET display_name=?,updated_at=?,last_login_at=? WHERE id=?",blank(name)?null:name,now,now,existing);jdbc.update("UPDATE user_identities SET email=? WHERE provider='GOOGLE' AND provider_subject=?",blank(email)?null:email,subject);return existing;}
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO users(id,display_name,created_at,updated_at,last_login_at) VALUES(?,?,?,?,?)",id,blank(name)?null:name,now,now,now);jdbc.update("INSERT INTO user_identities(id,user_id,provider,provider_subject,email,created_at) VALUES(?,?, 'GOOGLE',?,?,?)",UUID.randomUUID().toString(),id,subject,blank(email)?null:email,now);return id;
    }
    Map<String,Object> user(String subject){return jdbc.query("SELECT u.id,u.display_name,i.email FROM users u JOIN user_identities i ON i.user_id=u.id WHERE i.provider='GOOGLE' AND i.provider_subject=?",(rs,row)->Map.<String,Object>of("id",rs.getString("id"),"displayName",Objects.toString(rs.getString("display_name"),""),"email",Objects.toString(rs.getString("email"),"")),subject).stream().findFirst().orElse(null);}
    List<Map<String,Object>> saved(String userId){return jdbc.query("SELECT announcement_id,display_order,note,reminder_at FROM user_saved_announcements WHERE user_id=? ORDER BY display_order,created_at",(rs,row)->{Map<String,Object> value=new LinkedHashMap<>();value.put("announcementId",rs.getString(1));value.put("displayOrder",rs.getInt(2));value.put("note",rs.getString(3));value.put("reminderAt",rs.getString(4));return value;},userId);}
    void save(String userId,String announcementId,String note,String reminder){String now=Instant.now().toString();int next=jdbc.queryForObject("SELECT COALESCE(MAX(display_order),-1)+1 FROM user_saved_announcements WHERE user_id=?",Integer.class,userId);jdbc.update("INSERT INTO user_saved_announcements(user_id,announcement_id,display_order,note,reminder_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(user_id,announcement_id) DO UPDATE SET note=excluded.note,reminder_at=excluded.reminder_at,updated_at=excluded.updated_at",userId,announcementId,next,blank(note)?null:note,blank(reminder)?null:reminder,now,now);}
    void remove(String userId,String announcementId){jdbc.update("DELETE FROM user_saved_announcements WHERE user_id=? AND announcement_id=?",userId,announcementId);}
    void reorder(String userId,List<String> ids){transactions.executeWithoutResult(status->{int order=0;for(String id:ids)jdbc.update("UPDATE user_saved_announcements SET display_order=?,updated_at=? WHERE user_id=? AND announcement_id=?",order++,Instant.now().toString(),userId,id);});}
    void merge(String userId,List<Map<String,Object>> items){transactions.executeWithoutResult(status->{jdbc.update("UPDATE user_saved_announcements SET display_order=display_order+? WHERE user_id=?",items.size(),userId);int order=0;for(Map<String,Object> item:items){String id=String.valueOf(item.get("announcementId"));if(id.isBlank())continue;save(userId,id,text(item.get("note")),text(item.get("reminderAt")));jdbc.update("UPDATE user_saved_announcements SET display_order=? WHERE user_id=? AND announcement_id=?",order++,userId,id);}});}
    private String findId(String subject){List<String> ids=jdbc.queryForList("SELECT user_id FROM user_identities WHERE provider='GOOGLE' AND provider_subject=?",String.class,subject);return ids.isEmpty()?null:ids.getFirst();}
    private boolean blank(String value){return value==null||value.isBlank();} private String text(Object value){return value==null?"":String.valueOf(value).trim();}
}
