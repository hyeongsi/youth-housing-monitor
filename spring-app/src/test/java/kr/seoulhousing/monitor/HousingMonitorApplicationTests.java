package kr.seoulhousing.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HousingMonitorApplicationTests {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path root;

    @Test void preservesFailedSourceCatalogWhileReplacingSuccessfulSource() throws Exception {
        write("sources.json", List.of(source("good", "ok"), source("bad", "fail")));
        write("all-announcements.json", Map.of("items", List.of(old("good", "old-good"), old("bad", "old-bad"))));
        Map<String, Object> result = new StubMonitor(root, Map.of("ok", lhPage())).refresh();
        List<Map<String, Object>> items = catalogItems();
        assertEquals(1, result.get("newItemCount"));
        assertEquals(2, items.size());
        assertTrue(items.stream().anyMatch(item -> item.get("sourceId").equals("bad") && item.get("title").equals("old-bad")));
        assertTrue(items.stream().anyMatch(item -> item.get("sourceId").equals("good") && item.get("title").equals("새 LH 공고")));
        assertFalse(items.stream().anyMatch(item -> item.get("title").equals("old-good")));
    }

    @Test void keepsCatalogUntouchedWhenEveryCatalogSourceFails() throws Exception {
        write("sources.json", List.of(source("one", "fail-one"), source("two", "fail-two")));
        write("all-announcements.json", Map.of("items", List.of(old("one", "last-good"))));
        Path catalog = data().resolve("all-announcements.json"); String before = Files.readString(catalog);
        new StubMonitor(root, Map.of()).refresh();
        assertEquals(before, Files.readString(catalog));
        Map<?, ?> notifications = mapper.readValue(data().resolve("notifications.json").toFile(), Map.class);
        assertEquals(2, ((List<?>) notifications.get("sourceFailures")).size());
    }

    @Test void emptyParserResultPreservesTheSourceLastGoodCatalog() throws Exception {
        write("sources.json", List.of(source("empty", "empty")));
        write("all-announcements.json", Map.of("items", List.of(old("empty", "last-good"))));
        new StubMonitor(root, Map.of("empty", "<table><tr><th>공고일</th></tr></table>")).refresh();

        assertEquals("last-good", catalogItems().getFirst().get("title"));
        Map<?, ?> state = mapper.readValue(data().resolve("monitor-state.json").toFile(), Map.class);
        Map<?, ?> sources = (Map<?, ?>) state.get("sources");
        assertEquals("Parser returned no records", ((Map<?, ?>) sources.get("empty")).get("error"));
    }

    @Test void lhColumnsAndYouthStatusCodesAreNormalizedAndValidated() throws Exception {
        HousingMonitorService service = new StubMonitor(root, Map.of());
        List<Map<String, Object>> lh = service.parseLh(lhPage(), source("lh", "unused"), "2026-10-25T10:00:00");
        assertEquals("2026-10-22", lh.getFirst().get("announcedAt"));
        assertEquals("2026-11-03", lh.getFirst().get("applicationEnd"));
        assertEquals("접수중", lh.getFirst().get("status"));
        write("sources.json", List.of(Map.of("id", "youth", "name", "청년안심", "kind", "youth-safe-list", "endpoint", "youth")));
        StubMonitor youthService = new StubMonitor(root, Map.of());
        youthService.youthResponse = mapper.readTree("{\"pagingInfo\":{\"totPage\":1},\"resultList\":[{\"boardId\":\"10\",\"nttSj\":\"추가모집 공고\",\"optn1\":\"2026.10.22\",\"optn2\":\"1\",\"optn3\":\"서울청년주택운영사\",\"optn4\":\"2026.10.30\",\"optn5\":\"2\"},{\"boardId\":\"11\",\"nttSj\":\"일반모집 공고\",\"optn1\":\"2026.10.22\",\"optn2\":\"1\",\"optn3\":\"일반모집운영사\",\"optn4\":\"2026.10.30\",\"optn5\":\"1\"},{\"boardId\":\"12\",\"nttSj\":\"알수없는 공고\",\"optn1\":\"2026.10.22\",\"optn2\":\"1\",\"optn3\":\"미상운영사\",\"optn4\":\"2026.10.30\",\"optn5\":\"99\"}]}");
        youthService.refresh();
        List<Map<String, Object>> youth = catalogItems();
        Map<String, Object> extraRecruitment = youth.stream().filter(item -> item.get("title").equals("추가모집 공고")).findFirst().orElseThrow();
        assertEquals("서울청년주택운영사", extraRecruitment.get("provider"));
        assertEquals("추가모집", extraRecruitment.get("status"));
        assertTrue(HousingMonitorService.ALLOWED_STATUSES.contains(extraRecruitment.get("status")), "상태 필터에 표시될 추가모집 값을 허용한다");
        assertEquals("2026-10-22", extraRecruitment.get("announcedAt"));
        assertEquals("2026-10-30", extraRecruitment.get("applicationEnd"));
        assertEquals("모집", youth.stream().filter(item -> item.get("title").equals("일반모집 공고")).findFirst().orElseThrow().get("status"));
        assertEquals("목록 기재 없음", youth.stream().filter(item -> item.get("title").equals("알수없는 공고")).findFirst().orElseThrow().get("status"));
    }

    @Test void overviewUsesTypeCorrectDefaultsWhenDataFilesDoNotExist() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new HousingDataController(mapper, root.toString())).build();
        mvc.perform(get("/api/overview")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").isMap()).andExpect(jsonPath("$.programs").isArray())
                .andExpect(jsonPath("$.notices").isArray()).andExpect(jsonPath("$.monitor").isMap())
                .andExpect(jsonPath("$.discoveries").isArray()).andExpect(jsonPath("$.notifications").isMap())
                .andExpect(jsonPath("$.allAnnouncements").isMap());
    }

    @Test void timeoutIsRecordedAsAFailedSourceAndDoesNotEraseItsData() throws Exception {
        write("sources.json", List.of(source("slow", "timeout")));
        write("all-announcements.json", Map.of("items", List.of(old("slow", "last-good"))));
        StubMonitor service = new StubMonitor(root, Map.of()); service.timeoutUrls = true; service.refresh();
        assertEquals("last-good", catalogItems().getFirst().get("title"));
        Map<?, ?> state = mapper.readValue(data().resolve("monitor-state.json").toFile(), Map.class);
        Map<?, ?> sources = (Map<?, ?>) state.get("sources");
        assertEquals("HTTP timeout", ((Map<?, ?>) sources.get("slow")).get("error"));
    }

    private Map<String, Object> source(String id, String url) { return Map.of("id", id, "name", id, "kind", "lh-list", "url", url); }
    private Map<String, Object> old(String sourceId, String title) { return Map.of("id", sourceId + "-old", "sourceId", sourceId, "title", title, "announcedAt", "2026-01-01"); }
    private String lhPage() { return "<table><tr><th>번호</th><th>주택유형</th><th>지역</th><th>공고명</th><th>첨부파일</th><th>공고일</th><th>접수 마감일</th><th>공고 상태</th></tr>" + "<tr data-id1='pan' data-id2='sys' data-id3='up' data-id4='type'><td>1</td><td>매입임대</td><td>서울</td><td><a class='wrtancInfoBtn'>새 LH 공고</a></td><td>첨부</td><td>2026.10.22</td><td>2026.11.03</td><td>접수중</td></tr></table>"; }
    private Path data() throws Exception { Path data = root.resolve("data"); Files.createDirectories(data); return data; }
    private void write(String file, Object value) throws Exception { mapper.writeValue(data().resolve(file).toFile(), value); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> catalogItems() throws Exception { return (List<Map<String, Object>>) mapper.readValue(data().resolve("all-announcements.json").toFile(), Map.class).get("items"); }

    private final class StubMonitor extends HousingMonitorService {
        private final Map<String, String> pages; private boolean timeoutUrls; private JsonNode youthResponse;
        StubMonitor(Path root, Map<String, String> pages) { super(mapper, root.toString()); this.pages = pages; }
        @Override protected String get(String url) throws Exception { if (timeoutUrls || url.startsWith("timeout")) throw new HttpTimeoutException("slow response"); if (!pages.containsKey(url)) throw new IllegalStateException("unavailable"); return pages.get(url); }
        @Override protected JsonNode post(String url, String body) throws Exception { if (youthResponse == null) throw new IllegalStateException("unavailable"); return youthResponse; }
    }
}
