package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AnnouncementOperatingPathTests {
    @TempDir Path root;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void importsTheProductionFixturesWithoutLossAndServesDatabaseBackedApis() throws Exception {
        Path projectData=Path.of(System.getProperty("user.dir")).getParent().resolve("data").normalize();
        Path data=Files.createDirectories(root.resolve("data"));
        Files.copy(projectData.resolve("all-announcements.json"),data.resolve("all-announcements.json"),StandardCopyOption.REPLACE_EXISTING);
        Files.copy(projectData.resolve("announcement-details.json"),data.resolve("announcement-details.json"),StandardCopyOption.REPLACE_EXISTING);
        Map<String,Object> expectedCatalog=mapper.readValue(data.resolve("all-announcements.json").toFile(),new TypeReference<>(){});
        Map<String,Object> expectedDetails=mapper.readValue(data.resolve("announcement-details.json").toFile(),new TypeReference<>(){});
        Context context=context(root);
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());

        assertEquals(context.jdbc().queryForObject("SELECT COUNT(*) FROM announcements WHERE active=1 AND content_kind='ANNOUNCEMENT'",Integer.class),repository.catalog().get("itemCount"));
        long expectedDetailCount=((Map<?,?>)expectedDetails.get("items")).keySet().stream()
                .map(String::valueOf).filter(id->repository.announcement(id)!=null).count();
        int expectedOptions=detailOptionCountForImportedAnnouncements(expectedDetails,repository);
        assertEquals(expectedDetailCount,((Map<?,?>)repository.details().get("items")).size());
        assertEquals(expectedOptions,normalizedOptionCount(repository.details()));
        for(Object raw:((List<?>)expectedCatalog.get("items")).subList(0,Math.min(20,((List<?>)expectedCatalog.get("items")).size()))) {
            assertNotNull(repository.announcement(String.valueOf(((Map<?,?>)raw).get("id"))));
        }

        HousingDataController controller=new HousingDataController(mapper,root.toString(),repository);
        Map<String,Object> overview=controller.overview();
        assertEquals(repository.catalog().get("itemCount"),((Map<?,?>)overview.get("catalogSummary")).get("itemCount"));
        assertFalse(overview.containsKey("allAnnouncements"));
        String detailId=String.valueOf(((Map<?,?>)expectedDetails.get("items")).keySet().iterator().next());
        assertEquals(200,controller.announcement(detailId).getStatusCode().value());
        SingleLookupRepository singleLookup=new SingleLookupRepository(context,root,mapper);
        MobileDataService mobile=new MobileDataService(mapper,root.toString(),singleLookup);
        assertEquals(detailId,mobile.announcement(detailId).get("id"));
        assertNotNull(mobile.detail(detailId));
        NotificationStore store=new NotificationStore(context.jdbc(),mapper);
        store.saveDevice("device","test","token",null);
        MobileApiController mobileApi=new MobileApiController(store,mobile,java.time.Duration.ofMinutes(10));
        assertEquals(200,mobileApi.announcement("device","Bearer token",detailId).getStatusCode().value());
        assertEquals(0,singleLookup.catalogCalls); assertEquals(0,singleLookup.detailsCalls);
        assertEquals(2,singleLookup.announcementCalls); assertEquals(2,singleLookup.detailCalls);

        new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        assertEquals(((List<?>)expectedCatalog.get("items")).size(),context.jdbc().queryForObject("SELECT COUNT(*) FROM announcements",Integer.class));
    }

    @Test void missingStaleAndCorruptCompatibilityJsonProduceTheSameDatabaseNewItems() throws Exception {
        List<String> ids=new ArrayList<>();
        for(String mode:List.of("missing","stale","corrupt")) {
            Path scenario=root.resolve(mode); Path data=Files.createDirectories(scenario.resolve("data"));
            mapper.writeValue(data.resolve("all-announcements.json").toFile(),Map.of("checkedAt","2026-11-01T09:00:00","items",List.of(baseline())));
            mapper.writeValue(data.resolve("sources.json").toFile(),List.of(Map.of("id","lh","name","LH","kind","lh-list","url","fixture")));
            Context context=context(scenario);
            AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,scenario.toString());
            if(mode.equals("missing")) Files.delete(data.resolve("all-announcements.json"));
            if(mode.equals("stale")) mapper.writeValue(data.resolve("all-announcements.json").toFile(),Map.of("items",List.of()));
            if(mode.equals("corrupt")) Files.writeString(data.resolve("all-announcements.json"),"{broken");
            Map<String,Object> result=new FixtureMonitor(mapper,scenario,repository).refresh();
            assertEquals(1,result.get("newItemCount"),mode);
            String id=String.valueOf(((Map<?,?>)((List<?>)result.get("newItems")).getFirst()).get("id")); ids.add(id);
            Map<?,?> notifications=mapper.readValue(data.resolve("notifications.json").toFile(),Map.class);
            assertEquals(id,String.valueOf(((Map<?,?>)((List<?>)notifications.get("actionable")).getFirst()).get("id")),mode);
            Map<?,?> exported=mapper.readValue(data.resolve("all-announcements.json").toFile(),Map.class);
            assertEquals(1,exported.get("schemaVersion")); assertEquals(result.get("checkedAt"),exported.get("checkedAt"));
            assertEquals("full",exported.get("mode")); assertEquals(repository.catalog().get("itemCount"),exported.get("itemCount"));
            assertTrue(exported.get("items") instanceof List<?>);
        }
        assertEquals(1,new HashSet<>(ids).size());
    }

    private Context context(Path scenario){ DriverManagerDataSource source=new DriverManagerDataSource("jdbc:sqlite:"+scenario.resolve("data/test.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL"); return new Context(new JdbcTemplate(source),new DataSourceTransactionManager(source)); }
    private Map<String,Object> baseline(){ return Map.ofEntries(Map.entry("id","old-id"),Map.entry("sourceId","lh"),Map.entry("sourceName","LH"),Map.entry("provider","LH"),Map.entry("title","기존 공고"),Map.entry("housingType","국민임대"),Map.entry("area","서울"),Map.entry("announcedAt","2026-10-01"),Map.entry("applicationEnd","2026-10-31"),Map.entry("status","접수마감"),Map.entry("officialUrl","https://example.test/old"),Map.entry("retrievedAt","2026-11-01T09:00:00")); }
    private int detailOptionCount(Map<String,Object> details){ int count=0; for(Object value:((Map<?,?>)details.get("items")).values()){ Object options=((Map<?,?>)value).get("costOptions"); if(options instanceof List<?> list) count+=list.size(); } return count; }
    private int detailOptionCountForImportedAnnouncements(Map<String,Object> details,AnnouncementRepository repository){int count=0;for(Map.Entry<?,?> entry:((Map<?,?>)details.get("items")).entrySet()){if(repository.announcement(String.valueOf(entry.getKey()))==null)continue;Object options=((Map<?,?>)entry.getValue()).get("costOptions");if(options instanceof List<?> list)count+=list.size();}return count;}
    private int normalizedOptionCount(Map<String,Object> details){ int count=0; for(Object detail:((Map<?,?>)details.get("items")).values()) for(Object site:(List<?>)((Map<?,?>)detail).get("sites")) for(Object unit:(List<?>)((Map<?,?>)site).get("units")) count+=((List<?>)((Map<?,?>)unit).get("rentalOptions")).size(); return count; }
    private String page(){ return "<table><tr><th>번호</th><th>주택유형</th><th>지역</th><th>공고명</th><th>첨부파일</th><th>공고일</th><th>접수 마감일</th><th>공고 상태</th></tr><tr data-id1='new-pan' data-id2='sys' data-id3='up' data-id4='type'><td>1</td><td>매입임대</td><td>서울</td><td><a class='wrtancInfoBtn'>신규 공고</a></td><td>첨부</td><td>2026.11.06</td><td>2026.11.30</td><td>접수중</td></tr></table>"; }
    private record Context(JdbcTemplate jdbc,DataSourceTransactionManager transactions) {}
    private static final class SingleLookupRepository extends AnnouncementRepository {
        int catalogCalls; int detailsCalls; int announcementCalls; int detailCalls;
        SingleLookupRepository(Context context,Path root,ObjectMapper mapper){ super(context.jdbc(),context.transactions(),mapper,root.toString()); }
        @Override public Map<String,Object> catalog(){ catalogCalls++; throw new AssertionError("모바일 단건 API가 전체 catalog를 조회했습니다."); }
        @Override public Map<String,Object> details(){ detailsCalls++; throw new AssertionError("모바일 단건 API가 전체 details를 조회했습니다."); }
        @Override public Map<String,Object> announcement(String id){ announcementCalls++; return super.announcement(id); }
        @Override public Map<String,Object> detail(String id){ detailCalls++; return super.detail(id); }
    }
    private final class FixtureMonitor extends HousingMonitorService {
        FixtureMonitor(ObjectMapper mapper,Path scenario,AnnouncementRepository repository){ super(mapper,scenario.toString(),repository); }
        @Override protected String get(String url){ return page(); }
    }
}
