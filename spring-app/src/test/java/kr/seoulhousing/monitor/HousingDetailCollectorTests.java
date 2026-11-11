package kr.seoulhousing.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HousingDetailCollectorTests {
    @TempDir Path root;

    @Test void collectsMultiSiteLhDetailAndPreservesItWhenTheNextAttemptFails() throws Exception {
        Files.createDirectories(root.resolve("data"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("data/test.db") + "?foreign_keys=on");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AnnouncementRepository repository = new AnnouncementRepository(jdbc, new DataSourceTransactionManager(dataSource), new ObjectMapper(), root.toString());
        Map<String, Object> source = Map.of("id", "lh", "name", "LH", "kind", "lh-list");
        String run = repository.startRun("TEST", "2026-11-07T09:00:00");
        repository.saveSuccessfulSource(run, source, List.of(announcement("처음 공고", "2026-11-07T09:00:00")), "2026-11-07T09:00:00");

        HousingDetailCollector collector = new FixtureCollector(repository, fixture());
        Map<String, Object> result = collector.collect();

        assertEquals(1, result.get("parsed"), result.toString());
        Map<String, Object> detail = repository.detail("lh-detail");
        List<?> sites = (List<?>) detail.get("sites");
        assertEquals(2, sites.size());
        Map<?, ?> firstUnit = (Map<?, ?>) ((List<?>) ((Map<?, ?>) sites.getFirst()).get("units")).getFirst();
        Map<?, ?> option = (Map<?, ?>) ((List<?>) firstUnit.get("rentalOptions")).getFirst();
        assertEquals(53_757_000L, ((Number) option.get("depositWon")).longValue());
        assertEquals(543_070L, ((Number) option.get("monthlyRentWon")).longValue());
        String localPath = jdbc.queryForObject("SELECT local_path FROM announcement_documents", String.class);
        assertNotNull(localPath);
        assertTrue(Files.exists(root.resolve("data").resolve(localPath)));

        String changedRun = repository.startRun("TEST", "2027-01-01T09:00:00");
        repository.saveSuccessfulSource(changedRun, source, List.of(announcement("변경된 공고", "2027-01-01T09:00:00")), "2027-01-01T09:00:00");
        new FixtureCollector(repository, null).collect();

        assertEquals(2, ((List<?>) repository.detail("lh-detail").get("sites")).size());
        assertEquals("PARSED", jdbc.queryForObject("SELECT detail_status FROM announcements WHERE id='lh-detail'", String.class));
        assertEquals("FAILED", jdbc.queryForObject("SELECT detail_attempt_status FROM announcements WHERE id='lh-detail'", String.class));
    }

    private Map<String, Object> announcement(String title, String retrievedAt) {
        return Map.ofEntries(
                Map.entry("id", "lh-detail"), Map.entry("sourceId", "lh"), Map.entry("sourceName", "LH"),
                Map.entry("provider", "LH"), Map.entry("title", title), Map.entry("housingType", "공공임대"),
                Map.entry("announcedAt", "2026-11-01"), Map.entry("applicationEnd", "2026-11-30"),
                Map.entry("status", "접수중"), Map.entry("officialUrl", "https://example.test/detail"), Map.entry("retrievedAt", retrievedAt));
    }

    private String fixture() {
        return """
                <section><h3>공급정보</h3>
                  <div id="wrtancSbdTab0"><h4 class="tit2 mgt0">안성아양 B-4</h4>
                    <ul><li>소재지 : 경기도 안성시 아양로 73</li><li>입주예정월 : 2027년 1월</li></ul>
                    <script>var lat_0 = "37.004"; var lng_0 = "127.258";</script>
                    <table><caption>주택형 안내</caption><thead><tr><th>주택형</th><th>전용면적(㎡)</th><th>금회공급 세대수</th><th>임대보증금(원)</th><th>월임대료(원)</th></tr></thead>
                    <tbody><tr><th>74.9300A</th><td>74.93</td><td>3</td><td>53,757,000</td><td>543,070</td></tr></tbody></table>
                  </div>
                  <div id="wrtancSbdTab1"><h4 class="tit2 mgt0">평택소사벌 B-2</h4>
                    <ul><li>소재지 : 경기도 평택시 비전4로 175</li><li>입주예정월 : 2027년 2월</li></ul>
                    <script>var lat_1 = "36.99"; var lng_1 = "127.11";</script>
                    <table><caption>주택형 안내</caption><thead><tr><th>주택형</th><th>전용면적(㎡)</th><th>금회공급 세대수</th><th>임대보증금(원)</th><th>월임대료(원)</th></tr></thead>
                    <tbody><tr><th>84.9100A</th><td>84.91</td><td>1</td><td>65,850,000</td><td>598,030</td></tr></tbody></table>
                  </div>
                </section>
                """;
    }

    private final class FixtureCollector extends HousingDetailCollector {
        private final String html;
        private FixtureCollector(AnnouncementRepository repository, String html) {
            super(repository, new LhDetailParser(), root.toString(), 10);
            this.html = html;
        }
        @Override protected String fetch(String url) throws Exception {
            if (html == null) throw new IOException("timeout");
            return html;
        }
    }
}
