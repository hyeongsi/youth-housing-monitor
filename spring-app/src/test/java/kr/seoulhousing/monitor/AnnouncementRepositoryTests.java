package kr.seoulhousing.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnnouncementRepositoryTests {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void importsLegacyOnlyOnceAndMapsAnnouncedAtToAnnouncementDate() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(), Map.of("items", List.of(announcement("one"))));
        mapper.writeValue(root.resolve("data/announcement-details.json").toFile(), Map.of("items", Map.of()));
        TestContext context=context();
        AnnouncementRepository first=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        AnnouncementRepository second=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        assertEquals(1, context.jdbc().queryForObject("SELECT COUNT(*) FROM announcements",Integer.class));
        assertEquals("2026-11-01", first.announcement("one").get("announcementDate"));
        assertEquals(1, second.catalog().get("itemCount"));
        assertEquals(1, context.jdbc().queryForObject("PRAGMA foreign_keys",Integer.class));
    }

    @Test void storesMultiSiteHierarchyAndFiltersWithinOneRentalOption() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(), Map.of("items", List.of(announcement("multi"))));
        TestContext context=context();
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        repository.saveDetailHierarchy("multi","PARSED",List.of(
                site("관악", "관악구", 42, List.of(option(50_000_000,900_000),option(100_000_000,400_000))),
                site("중랑", "중랑구", 59, List.of(option(80_000_000,450_000)))
        ),"2026-11-06T10:00:00");

        Map<String,Object> detail=repository.detail("multi");
        assertEquals(2, ((List<?>)detail.get("sites")).size());
        assertTrue(repository.search("관악구",40d,50d,60_000_000L,500_000L).isEmpty(), "서로 다른 임대 조건 행의 보증금과 월세를 합치면 안 된다");
        assertEquals(1, repository.search("중랑구",55d,65d,90_000_000L,500_000L).size());
    }

    @Test void successfulSourceReplacesOnlyItsRowsAndFailedSourcePreservesLastGoodData() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(), Map.of("items", List.of(announcement("old"))));
        TestContext context=context();
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        String firstRun=repository.startRun("TEST","2026-11-06T10:00:00");
        repository.saveSuccessfulSource(firstRun,Map.of("id","fixture","name","Fixture","kind","fixture"),List.of(announcement("new")),"2026-11-06T10:00:00");
        assertNull(repository.announcement("old"));
        assertNotNull(repository.announcement("new"));
        String failedRun=repository.startRun("TEST","2026-11-06T11:00:00");
        repository.saveFailedSource(failedRun,Map.of("id","fixture","name","Fixture","kind","fixture"),"2026-11-06T11:00:00","timeout");
        assertNotNull(repository.announcement("new"));
        assertEquals(1,context.jdbc().queryForObject("SELECT COUNT(*) FROM collection_source_runs WHERE status='FAILED'",Integer.class));
    }

    @Test void firstSuccessfulSourceIsABaselineAndLaterIdsAreReturnedFromTheCommittedDatabaseResult() throws Exception {
        Files.createDirectories(root.resolve("data"));
        TestContext context=context();
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        Map<String,Object> source=Map.of("id","fixture","name","Fixture","kind","fixture");
        String baselineRun=repository.startRun("TEST","2026-11-06T09:00:00");
        assertTrue(repository.saveSuccessfulSource(baselineRun,source,List.of(announcement("baseline")),"2026-11-06T09:00:00").newItems().isEmpty());
        String nextRun=repository.startRun("TEST","2026-11-06T10:00:00");
        AnnouncementRepository.SourceResult result=repository.saveSuccessfulSource(nextRun,source,List.of(announcement("baseline"),announcement("new-id")),"2026-11-06T10:00:00");
        assertEquals(List.of("new-id"),result.newItems().stream().map(item->String.valueOf(item.get("id"))).toList());
    }

    @Test void collectionUpdateRecordsStatusAndApplicationDateChanges() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(),Map.of("items",List.of(announcement("tracked"))));
        TestContext context=context();
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        Map<String,Object> changed=new java.util.LinkedHashMap<>(announcement("tracked"));
        changed.put("status","모집마감");changed.put("applicationEnd","2026-12-10");changed.put("retrievedAt","2026-11-07T10:00:00");
        String run=repository.startRun("TEST","2026-11-07T10:00:00");
        repository.saveSuccessfulSource(run,Map.of("id","fixture","name","Fixture","kind","fixture"),List.of(changed),"2026-11-07T10:00:00");

        List<String> changes=context.jdbc().queryForList("SELECT category || ':' || field_path FROM announcement_change_events WHERE announcement_id='tracked' ORDER BY field_path",String.class);
        assertEquals(List.of("RECRUITMENT:공고.모집 상태","SCHEDULE:공고.접수 마감"),changes);
        List<Map<String,Object>> apiChanges=repository.changes("tracked");
        assertEquals("모집마감",apiChanges.stream().filter(change->"공고.모집 상태".equals(change.get("fieldPath"))).findFirst().orElseThrow().get("after"));
        assertEquals(200,new HousingDataController(mapper,root.toString(),repository).announcementChanges("tracked").getStatusCode().value());
    }

    @Test void persistsRentalExtrasForDetailRevisionComparison() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(), Map.of("items", List.of(announcement("rental-extras"))));
        TestContext context=context();
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        Map<String,Object> rentalOption=new java.util.LinkedHashMap<>(option(50_000_000,900_000));
        rentalOption.put("managementFeeRawText","관리비 30,000원");
        rentalOption.put("managementFeeWon",30_000L);
        rentalOption.put("conversionConditionText","보증금 전환 불가");
        repository.saveDetailHierarchy("rental-extras","PARSED",List.of(site("관악", "관악구", 42, List.of(rentalOption))),"2026-11-06T10:00:00");
        Map<String,Object> before=repository.detail("rental-extras");

        Map<?,?> savedOption=(Map<?,?>)((List<?>)((Map<?,?>)((List<?>)repository.detail("rental-extras").get("sites")).getFirst()).get("units")).getFirst();
        Map<?,?> values=(Map<?,?>)((List<?>)savedOption.get("rentalOptions")).getFirst();
        assertEquals(30_000L,((Number)values.get("managementFeeWon")).longValue());
        assertEquals("보증금 전환 불가",values.get("conversionConditionText"));

        rentalOption.put("managementFeeWon",35_000L);
        rentalOption.put("conversionConditionText","보증금 전환 가능");
        repository.saveDetailHierarchy("rental-extras","PARSED",List.of(site("관악", "관악구", 42, List.of(rentalOption))),"2026-11-07T10:00:00");
        Map<String,Object> after=repository.detail("rental-extras");
        DocumentExtractionRepository extractions=new DocumentExtractionRepository(context.jdbc(),mapper,repository);
        String revisionId=extractions.recordRevision("rental-extras","TEST",null,null,null,before,after,"2026-11-07T10:00:00");
        extractions.recordChangeEvents("rental-extras",revisionId,before,after,"2026-11-07T10:00:00");
        List<String> fields=context.jdbc().queryForList("SELECT field_path FROM announcement_change_events WHERE announcement_id='rental-extras' ORDER BY field_path",String.class);
        assertTrue(fields.stream().anyMatch(field->field.endsWith("관리비")));
        assertTrue(fields.stream().anyMatch(field->field.endsWith("전환 조건")));
    }

    @Test void comparisonPreservesRequestedOrderAndExcludesUnknownAnnouncement() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(),Map.of("items",List.of(announcement("first"),announcement("second"))));
        TestContext context=context();
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        Map<String,Object> result=repository.compare(List.of("second","missing","first"));
        assertEquals(List.of("second","first"),((List<Map<String,Object>>)result.get("items")).stream().map(item->String.valueOf(((Map<?,?>)item.get("announcement")).get("id"))).toList());
        assertEquals("missing",((List<Map<String,Object>>)result.get("excluded")).getFirst().get("id"));
        List<?> responseItems=(List<?>)new HousingDataController(mapper,root.toString(),repository).compareAnnouncements("second,first").get("items");
        assertEquals(2,responseItems.size());
    }

    @Test void nonExactCanonicalKeysAreIsolatedPerAnnouncement() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(),Map.of("items",List.of(announcement("first"),announcement("second"))));
        TestContext context=context();
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        repository.saveDetailHierarchy("first","PARSED",nonExactSites("첫 공고",37.1),"2026-11-06T10:00:00");
        repository.saveDetailHierarchy("second","PARSED",nonExactSites("둘째 공고",37.9),"2026-11-06T10:00:00");
        for(String key:List.of("LANDMARK","DISTRICT","UNKNOWN")) assertEquals(2,context.jdbc().queryForObject("SELECT COUNT(*) FROM locations WHERE canonical_key LIKE ?",Integer.class,"%shared:"+key+"%"),key);
        assertEquals(List.of("첫 공고 LANDMARK","첫 공고 DISTRICT","첫 공고 UNKNOWN"),((List<?>)repository.detail("first").get("sites")).stream().map(site->String.valueOf(((Map<?,?>)site).get("name"))).toList());
        assertEquals(List.of("둘째 공고 LANDMARK","둘째 공고 DISTRICT","둘째 공고 UNKNOWN"),((List<?>)repository.detail("second").get("sites")).stream().map(site->String.valueOf(((Map<?,?>)site).get("name"))).toList());
        assertEquals(6,context.jdbc().queryForObject("SELECT COUNT(DISTINCT address) FROM locations",Integer.class));
    }

    @Test void pagesCatalogFiltersInSqlAndKeepsRentalConditionsOnOneRow() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(), Map.of("items", List.of(announcement("first"), announcement("second"), announcement("closed"), announcement("unknown"))));
        TestContext context=context();
        AnnouncementRepository repository=new AnnouncementRepository(context.jdbc(),context.transactions(),mapper,root.toString());
        repository.saveDetailHierarchy("first","PARSED",List.of(site("first", "관악구", 42, List.of(option(50_000_000,900_000)))),"2026-11-06T10:00:00");
        repository.saveDetailHierarchy("second","PARSED",List.of(site("second", "관악구", 55, List.of(option(100_000_000,400_000)))),"2026-11-06T10:00:00");
        repository.saveDetailHierarchy("closed","PARSED",List.of(site("closed", "중랑구", 59, List.of(option(80_000_000,450_000)))),"2026-11-06T10:00:00");
        context.jdbc().update("UPDATE announcements SET title=?,announcement_date=?,application_end=?,status=? WHERE id=?","첫 번째 공고","2099-01-03","2099-02-01","접수중","first");
        context.jdbc().update("UPDATE announcements SET title=?,announcement_date=?,application_end=?,status=? WHERE id=?","두 번째 공고","2099-01-02","2099-01-20","접수중","second");
        context.jdbc().update("UPDATE announcements SET title=?,announcement_date=?,application_end=?,status=? WHERE id=?","추가모집 마감 공고","2099-01-01","2099-01-10","마감","closed");
        context.jdbc().update("UPDATE announcements SET title=?,announcement_date=?,application_end=?,status=? WHERE id=?","상세 미확인 공고","2099-01-04","2099-02-10","접수중","unknown");
        context.jdbc().update("UPDATE announcements SET title=?,status=? WHERE id=?","추가모집 상태 공고","추가모집","first");
        context.jdbc().update("UPDATE announcements SET title=?,status=? WHERE id=?","제목으로 표시된 추가모집 공고","접수중","second");

        AnnouncementRepository.CatalogQuery mismatched=new AnnouncementRepository.CatalogQuery(1,10,"관악구","","","","공고",60_000_000L,500_000L,40d,50d,"LATEST",true);
        assertEquals(0,((List<?>)repository.page(mismatched).get("items")).size(),"보증금과 월세가 서로 다른 임대 조건 행에서 조합되면 안 된다");
        AnnouncementRepository.CatalogQuery matching=new AnnouncementRepository.CatalogQuery(1,1,"관악구","","","","제목으로",110_000_000L,500_000L,50d,60d,"LATEST",true);
        Map<String,Object> page=repository.page(matching);
        assertEquals(1,page.get("totalCount"));
        assertFalse((Boolean)page.get("hasNext"));
        assertEquals("second",((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("id"));

        Map<String,Object> firstPage=repository.page(new AnnouncementRepository.CatalogQuery(1,1,"","","","","",null,null,null,null,"LATEST",false));
        assertEquals(3,firstPage.get("totalCount"));
        assertTrue((Boolean)firstPage.get("hasNext"));
        assertEquals("unknown",((Map<?,?>)((List<?>)firstPage.get("items")).getFirst()).get("id"));
        Map<String,Object> deadlinePage=repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",null,null,null,null,"DEADLINE",false));
        assertEquals(List.of("second","first","unknown"),((List<?>)deadlinePage.get("items")).stream().map(item->String.valueOf(((Map<?,?>)item).get("id"))).toList());
        context.jdbc().update("UPDATE announcements SET application_end=? WHERE id=?",LocalDate.now().plusDays(2).toString(),"unknown");
        Map<String,Object> deadlineSoonPage=repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",null,null,null,null,"LATEST",false,true,false,List.of(),null,false,true));
        assertEquals(List.of("unknown"),((List<?>)deadlineSoonPage.get("items")).stream().map(item->String.valueOf(((Map<?,?>)item).get("id"))).toList(),"마감 임박 필터는 오늘부터 3일 이내 접수 마감 공고만 반환한다");
        assertEquals(true,((Map<?,?>)deadlineSoonPage.get("appliedFilters")).get("deadlineSoonOnly"));
        assertEquals(4,repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",null,null,null,null,"LATEST",true)).get("totalCount"));
        assertEquals(2,repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",null,null,null,null,"LATEST",false,false,false,List.of(),null)).get("totalCount"),"값 확인 공고만 보기에서는 상세 미확인 공고를 제외한다");
        assertEquals(0,repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",null,null,null,null,"LATEST",false,true,true,List.of(),null)).get("totalCount"),"저장 공고만 보기가 켜진 빈 저장 목록은 일반 목록을 반환하면 안 된다");
        assertEquals(1,repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",60_000_000L,500_000L,null,null,"LATEST",false,true,List.of(),null)).get("totalCount"),"미기재 포함은 비교할 임대 조건이 없는 공고만 허용한다");
        assertEquals(1,repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",null,null,null,null,"LATEST",false,true,List.of("second"),null)).get("totalCount"),"저장 공고 ID는 전체 결과의 서버 조건으로 적용한다");
        Map<String,Object> savedOrderPage=repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",null,null,null,null,"SAVED",false,true,true,List.of("second","first"),null));
        assertEquals(List.of("second","first"),((List<?>)savedOrderPage.get("items")).stream().map(item->String.valueOf(((Map<?,?>)item).get("id"))).toList(),"저장 공고는 브라우저에 저장한 순서를 유지한다");
        AnnouncementRepository.InterestQuery interest=new AnnouncementRepository.InterestQuery(true,List.of("관악구"),110_000_000L,500_000L,50d,60d,false);
        assertEquals(List.of("second"),((List<?>)repository.page(new AnnouncementRepository.CatalogQuery(1,10,"","","","","",null,null,null,null,"LATEST",false,true,List.of(),interest)).get("items")).stream().map(item->String.valueOf(((Map<?,?>)item).get("id"))).toList());
        Map<String,Object> supplementalPage=repository.page(new AnnouncementRepository.CatalogQuery(1,1,"","","OPEN","","",null,null,null,null,"LATEST",false,true,false,List.of(),null,true));
        assertEquals(2,supplementalPage.get("totalCount"),"원문 상태 또는 제목의 추가모집 표시는 서버에서 함께 필터링한다");
        assertTrue((Boolean)supplementalPage.get("hasNext"));
        assertEquals("first",((Map<?,?>)((List<?>)supplementalPage.get("items")).getFirst()).get("id"));
        assertEquals(true,((Map<?,?>)supplementalPage.get("appliedFilters")).get("supplementalOnly"));
        Map<String,Object> supplementalCostPage=repository.page(new AnnouncementRepository.CatalogQuery(1,10,"관악구","","OPEN","","",60_000_000L,1_000_000L,null,null,"LATEST",false,true,false,List.of(),null,true));
        assertEquals(List.of("first"),((List<?>)supplementalCostPage.get("items")).stream().map(item->String.valueOf(((Map<?,?>)item).get("id"))).toList(),"추가모집 조건은 지역·금액·진행 상태 조건과 AND로 결합한다");
        assertTrue(((List<?>)repository.facets().get("regions")).contains("서울"),"상세 위치가 없는 원본 지역도 facet에 포함한다");
    }

    private Map<String,Object> announcement(String id){ return Map.ofEntries(Map.entry("id",id),Map.entry("sourceId","fixture"),Map.entry("sourceName","Fixture"),Map.entry("provider","공급기관"),Map.entry("title","다중 공급 공고"),Map.entry("housingType","청년주택"),Map.entry("area","서울"),Map.entry("announcedAt","2026-11-01"),Map.entry("applicationEnd","2026-11-30"),Map.entry("status","접수중"),Map.entry("officialUrl","https://example.test/"+id),Map.entry("retrievedAt","2026-11-06T10:00:00")); }
    private Map<String,Object> site(String key,String district,double area,List<Map<String,Object>> options){ return Map.of("sourceSiteKey",key,"name",key+" 주택","location",Map.of("region1","서울특별시","region2",district,"address",district+" 테스트로 1","accuracy","EXACT","latitude",37.5,"longitude",127.0),"units",List.of(Map.of("sourceUnitKey",key+"-unit","unitType","A형","exclusiveAreaM2",area,"rentalOptions",options))); }
    private Map<String,Object> option(long deposit,long rent){ return Map.of("conditionText","전환 조건","depositWon",deposit,"monthlyRentWon",rent); }
    private List<Map<String,Object>> nonExactSites(String prefix,double latitude){ List<Map<String,Object>> sites=new ArrayList<>(); for(String accuracy:List.of("LANDMARK","DISTRICT","UNKNOWN")) sites.add(Map.of("sourceSiteKey",accuracy,"canonicalKey","shared:"+accuracy,"name",prefix+" "+accuracy,"location",Map.of("region1","서울특별시","region2","관악구","address",prefix+" "+accuracy,"accuracy",accuracy,"latitude",latitude,"longitude",126.9),"units",List.of())); return sites; }
    private TestContext context(){ DriverManagerDataSource source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("data/test.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL"); JdbcTemplate jdbc=new JdbcTemplate(source); return new TestContext(jdbc,new DataSourceTransactionManager(source)); }
    private record TestContext(JdbcTemplate jdbc,DataSourceTransactionManager transactions) {}
}
