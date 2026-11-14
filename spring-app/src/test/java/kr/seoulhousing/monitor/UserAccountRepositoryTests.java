package kr.seoulhousing.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserAccountRepositoryTests {
    @TempDir Path root;
    @Test void keepsSavedAnnouncementsPerGoogleAccountAndMergesLocalValues() throws Exception {
        Files.createDirectories(root.resolve("data")); ObjectMapper mapper=new ObjectMapper();
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(),Map.of("items",List.of(announcement("one"),announcement("two"))));
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("data/test.db")+"?foreign_keys=on"); JdbcTemplate jdbc=new JdbcTemplate(source); DataSourceTransactionManager transactions=new DataSourceTransactionManager(source);
        AnnouncementRepository announcements=new AnnouncementRepository(jdbc,transactions,mapper,root.toString()); UserAccountRepository users=new UserAccountRepository(jdbc,transactions,announcements);
        String user=users.upsertGoogle("google-subject","user@example.test","사용자"); users.save(user,"one","확인할 내용","2026-11-20T09:00");
        users.merge(user,List.of(Map.of("announcementId","two","note","두 번째","reminderAt","")));
        assertEquals(List.of("two","one"),users.saved(user).stream().map(item->String.valueOf(item.get("announcementId"))).toList());
        assertEquals("사용자",users.user("google-subject").get("displayName"));
    }
    private Map<String,Object> announcement(String id){return Map.ofEntries(Map.entry("id",id),Map.entry("sourceId","fixture"),Map.entry("sourceName","Fixture"),Map.entry("provider","공급기관"),Map.entry("title","공고 "+id),Map.entry("housingType","청년주택"),Map.entry("announcedAt","2026-11-01"),Map.entry("applicationEnd","2026-11-30"),Map.entry("status","접수중"),Map.entry("officialUrl","https://example.test/"+id),Map.entry("retrievedAt","2026-11-01T09:00:00"));}
}
