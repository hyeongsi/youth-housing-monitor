package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class AnnouncementRepository {
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Path data;

    public AnnouncementRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                  ObjectMapper mapper, @Value("${housing.root}") String root) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.data = Path.of(root).resolve("data").normalize();
        initialize();
        importLegacyIfEmpty();
    }

    private void initialize() {
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.execute("PRAGMA busy_timeout=5000");
        jdbc.execute("PRAGMA journal_mode=WAL");
        for (String statement : List.of(
                "CREATE TABLE IF NOT EXISTS announcement_sources (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, active INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS providers (id TEXT PRIMARY KEY, normalized_name TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS announcements (id TEXT PRIMARY KEY, source_id TEXT NOT NULL REFERENCES announcement_sources(id), source_announcement_key TEXT NOT NULL, provider_id TEXT REFERENCES providers(id), title TEXT NOT NULL, housing_type TEXT, announcement_date TEXT, application_start TEXT, application_end TEXT, status TEXT, official_url TEXT NOT NULL, detail_status TEXT NOT NULL, content_hash TEXT NOT NULL, first_seen_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, last_changed_at TEXT NOT NULL, active INTEGER NOT NULL, raw_summary_json TEXT, UNIQUE(source_id, source_announcement_key))",
                "CREATE TABLE IF NOT EXISTS locations (id TEXT PRIMARY KEY, canonical_key TEXT NOT NULL UNIQUE, region1 TEXT, region2 TEXT, region3 TEXT, address TEXT, latitude REAL, longitude REAL, accuracy TEXT NOT NULL, geocoded_at TEXT)",
                "CREATE TABLE IF NOT EXISTS housing_sites (id TEXT PRIMARY KEY, canonical_key TEXT NOT NULL UNIQUE, name TEXT, location_id TEXT REFERENCES locations(id))",
                "CREATE TABLE IF NOT EXISTS announcement_sites (id TEXT PRIMARY KEY, announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE, site_id TEXT NOT NULL REFERENCES housing_sites(id), source_site_key TEXT NOT NULL, display_order INTEGER NOT NULL, move_in_text TEXT, eligibility_text TEXT, UNIQUE(announcement_id, source_site_key))",
                "CREATE TABLE IF NOT EXISTS supply_units (id TEXT PRIMARY KEY, announcement_site_id TEXT NOT NULL REFERENCES announcement_sites(id) ON DELETE CASCADE, source_unit_key TEXT NOT NULL, unit_type TEXT, exclusive_area_m2 REAL, supply_count INTEGER, category_text TEXT, floor_text TEXT, room_text TEXT, display_order INTEGER NOT NULL, UNIQUE(announcement_site_id, source_unit_key))",
                "CREATE TABLE IF NOT EXISTS rental_options (id TEXT PRIMARY KEY, supply_unit_id TEXT NOT NULL REFERENCES supply_units(id) ON DELETE CASCADE, option_key TEXT NOT NULL, condition_text TEXT, deposit_won INTEGER, monthly_rent_won INTEGER, management_fee_text TEXT, display_order INTEGER NOT NULL, UNIQUE(supply_unit_id, option_key))",
                "CREATE TABLE IF NOT EXISTS supply_rooms (id TEXT PRIMARY KEY, supply_unit_id TEXT NOT NULL REFERENCES supply_units(id) ON DELETE CASCADE, building_text TEXT, floor_text TEXT, room_text TEXT, source_text TEXT)",
                "CREATE TABLE IF NOT EXISTS site_organizations (id TEXT PRIMARY KEY, announcement_site_id TEXT NOT NULL REFERENCES announcement_sites(id) ON DELETE CASCADE, role TEXT NOT NULL, organization_name TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS announcement_events (id TEXT PRIMARY KEY, announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE, event_type TEXT NOT NULL, starts_at TEXT, ends_at TEXT, status TEXT NOT NULL, description TEXT)",
                "CREATE TABLE IF NOT EXISTS eligibility_rules (id TEXT PRIMARY KEY, announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE, category TEXT NOT NULL, target_group TEXT, rank INTEGER, household_size INTEGER, operator TEXT, threshold_value NUMERIC, threshold_unit TEXT, description TEXT)",
                "CREATE TABLE IF NOT EXISTS announcement_detail_revisions (id TEXT PRIMARY KEY, announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE, action TEXT NOT NULL, extraction_id TEXT, document_hash TEXT, source_revision_id TEXT REFERENCES announcement_detail_revisions(id), before_json TEXT NOT NULL, after_json TEXT NOT NULL, created_at TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS announcement_documents (id TEXT PRIMARY KEY, announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE, document_url TEXT NOT NULL, document_type TEXT NOT NULL, content_hash TEXT, local_path TEXT, retrieved_at TEXT, parser_version TEXT, parse_status TEXT NOT NULL, parse_error TEXT)",
                "CREATE TABLE IF NOT EXISTS collection_runs (id TEXT PRIMARY KEY, trigger_type TEXT NOT NULL, started_at TEXT NOT NULL, finished_at TEXT, status TEXT NOT NULL, new_count INTEGER NOT NULL DEFAULT 0, changed_count INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS collection_source_runs (id TEXT PRIMARY KEY, collection_run_id TEXT NOT NULL REFERENCES collection_runs(id) ON DELETE CASCADE, source_id TEXT NOT NULL REFERENCES announcement_sources(id), started_at TEXT NOT NULL, finished_at TEXT, status TEXT NOT NULL, record_count INTEGER NOT NULL DEFAULT 0, new_count INTEGER NOT NULL DEFAULT 0, changed_count INTEGER NOT NULL DEFAULT 0, error TEXT)",
                "CREATE INDEX IF NOT EXISTS idx_announcements_active_date ON announcements(active, announcement_date DESC)",
                "CREATE INDEX IF NOT EXISTS idx_announcements_source ON announcements(source_id, active)",
                "CREATE INDEX IF NOT EXISTS idx_locations_region ON locations(region1, region2, region3)",
                "CREATE INDEX IF NOT EXISTS idx_units_area ON supply_units(exclusive_area_m2)",
                "CREATE INDEX IF NOT EXISTS idx_rental_cost ON rental_options(deposit_won, monthly_rent_won)"
        )) jdbc.execute(statement);
        ensureColumn("announcements", "detail_attempt_status TEXT");
        ensureColumn("announcements", "detail_last_attempt_at TEXT");
        ensureColumn("announcements", "detail_next_retry_at TEXT");
        ensureColumn("announcements", "detail_attempt_count INTEGER NOT NULL DEFAULT 0");
        ensureColumn("announcements", "detail_error TEXT");
        ensureColumn("announcements", "detail_parser_version TEXT");
        ensureColumn("announcements", "detail_source_hash TEXT");
        ensureColumn("announcements", "content_kind TEXT NOT NULL DEFAULT 'ANNOUNCEMENT'");
        jdbc.update("UPDATE announcements SET content_kind=CASE WHEN title LIKE '%당첨자%발표%' OR title LIKE '%예비자%발표%' OR title LIKE '%당첨자%명단%' OR title LIKE '%예비자%명단%' OR title LIKE '%서류%제출%' OR title LIKE '%서류%심사%' OR title LIKE '%계약%안내%' OR title LIKE '%입주%안내%' OR title LIKE '%결과%발표%' THEN 'NOTICE' ELSE 'ANNOUNCEMENT' END WHERE content_kind IS NULL OR content_kind='ANNOUNCEMENT'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_announcements_detail_candidates ON announcements(active, detail_attempt_status, detail_next_retry_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_detail_revisions_announcement ON announcement_detail_revisions(announcement_id, created_at DESC)");
    }

    private void ensureColumn(String table, String definition) {
        String name = definition.substring(0, definition.indexOf(' '));
        boolean exists = jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(column -> name.equals(column.get("name")));
        if (!exists) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
    }

    private void importLegacyIfEmpty() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM announcements", Integer.class) != 0) return;
        Map<String,Object> catalog = read("all-announcements.json", Map.of("items", List.of()));
        Map<String,Object> details = read("announcement-details.json", Map.of("items", Map.of()));
        List<Map<String,Object>> items = maps(catalog.get("items"));
        Map<String,Object> detailItems = map(details.get("items"));
        if (items.isEmpty()) return;
        String importedAt=Instant.now().toString();
        transactions.executeWithoutResult(status -> {
            for (Map<String,Object> item : items) upsertAnnouncement(item, false);
            for (Map.Entry<String,Object> entry : detailItems.entrySet()) {
                if (entry.getValue() instanceof Map<?,?> raw) importLegacyDetail(entry.getKey(), copy(raw));
            }
            jdbc.update("INSERT INTO collection_runs(id,trigger_type,started_at,finished_at,status,new_count,changed_count) VALUES(?, 'FULL', ?, ?, 'SUCCEEDED', 0, 0)",UUID.randomUUID().toString(),importedAt,importedAt);
        });
    }

    public String startRun(String trigger, String at) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO collection_runs(id,trigger_type,started_at,status) VALUES(?,?,?,'RUNNING')", id, trigger, at);
        return id;
    }

    public SourceResult saveSuccessfulSource(String runId, Map<String,Object> source, List<Map<String,Object>> records, String at) {
        return transactions.execute(status -> {
            ensureSource(source);
            String sourceId = text(source.get("id"));
            Set<String> existingIds = new HashSet<>(jdbc.queryForList("SELECT id FROM announcements WHERE source_id=?", String.class, sourceId));
            boolean hasBaseline = !existingIds.isEmpty() || jdbc.queryForObject("SELECT COUNT(*) FROM collection_source_runs WHERE source_id=? AND status='SUCCEEDED'", Integer.class, sourceId) > 0;
            int changed = 0;
            Set<String> ids = new HashSet<>();
            for (Map<String,Object> record : records) {
                ids.add(text(record.get("id")));
                changed += upsertAnnouncement(record, true) ? 1 : 0;
            }
            if (ids.isEmpty()) jdbc.update("UPDATE announcements SET active=0 WHERE source_id=?", text(source.get("id")));
            else {
                String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
                List<Object> args = new ArrayList<>(); args.add(source.get("id")); args.addAll(ids);
                jdbc.update("UPDATE announcements SET active=0 WHERE source_id=? AND id NOT IN (" + placeholders + ")", args.toArray());
            }
            List<String> newIds = hasBaseline ? records.stream().map(record -> text(record.get("id"))).filter(id -> !existingIds.contains(id)).toList() : List.of();
            List<Map<String,Object>> newItems = announcementsByIds(newIds);
            int added = newItems.size();
            String sourceRunId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO collection_source_runs(id,collection_run_id,source_id,started_at,finished_at,status,record_count,new_count,changed_count) VALUES(?,?,?,?,?,'SUCCEEDED',?,?,?)",
                    sourceRunId, runId, source.get("id"), at, at, records.size(), added, Math.max(0, changed - added));
            int inserted = (int) records.stream().filter(record -> !existingIds.contains(text(record.get("id")))).count();
            return new SourceResult(newItems, Math.max(0, changed - inserted));
        });
    }

    public void saveFailedSource(String runId, Map<String,Object> source, String at, String error) {
        ensureSource(source);
        jdbc.update("INSERT INTO collection_source_runs(id,collection_run_id,source_id,started_at,finished_at,status,error) VALUES(?,?,?,?,?,'FAILED',?)",
                UUID.randomUUID().toString(), runId, source.get("id"), at, at, error);
    }

    public void finishRun(String runId, String at, String status, int newCount, int changedCount) {
        jdbc.update("UPDATE collection_runs SET finished_at=?,status=?,new_count=?,changed_count=? WHERE id=?", at, status, newCount, changedCount, runId);
    }

    public Map<String,Object> catalog() {
        List<Map<String,Object>> items = jdbc.query("SELECT * FROM announcements WHERE active=1 AND content_kind='ANNOUNCEMENT' ORDER BY COALESCE(announcement_date,'') DESC, first_seen_at DESC", (rs,row) -> mapAnnouncement(rs));
        Map<String,Object> metadata=catalogMetadata();
        Map<String,Object> catalog=new LinkedHashMap<>();
        catalog.put("schemaVersion",1); catalog.put("checkedAt",metadata.get("checkedAt")); catalog.put("mode",metadata.get("mode")); catalog.put("itemCount",items.size()); catalog.put("items",items);
        return catalog;
    }

    public Map<String,Object> page(CatalogQuery query) {
        StringBuilder where=new StringBuilder(" WHERE a.active=1 AND a.content_kind='ANNOUNCEMENT'"); List<Object> args=new ArrayList<>();
        if(hasText(query.region())){
            String region="%"+query.region().trim()+"%";
            // 상세 위치가 아직 없는 목록 공고도 원본 지역값으로 검색할 수 있어야 한다.
            where.append(" AND (EXISTS (SELECT 1 FROM announcement_sites ast JOIN housing_sites hs ON hs.id=ast.site_id JOIN locations l ON l.id=hs.location_id WHERE ast.announcement_id=a.id AND (l.region1 LIKE ? OR l.region2 LIKE ? OR l.region3 LIKE ? OR l.address LIKE ?)) OR a.raw_summary_json LIKE ?)");
            for(int i=0;i<5;i++)args.add(region);
        }
        if(hasText(query.housingType())){where.append(" AND a.housing_type=?");args.add(query.housingType());}
        if(hasText(query.status())){
            switch(query.status()){
                case "OPEN" -> where.append(" AND COALESCE(a.application_end,'9999-12-31')>=date('now') AND a.status NOT LIKE '%마감%' AND a.status NOT LIKE '%종료%'");
                case "UPCOMING" -> where.append(" AND (a.status LIKE '%예정%' OR (a.application_start IS NOT NULL AND a.application_start>date('now'))) ");
                case "CLOSED" -> where.append(" AND (COALESCE(a.application_end,'9999-12-31')<date('now') OR a.status LIKE '%마감%' OR a.status LIKE '%종료%')");
                case "UNKNOWN" -> where.append(" AND (a.status IS NULL OR a.status='' OR a.status='목록 기재 없음')");
                default -> { where.append(" AND a.status=?");args.add(query.status()); }
            }
        }
        if(hasText(query.source())){where.append(" AND s.name=?");args.add(query.source());}
        for(String word:query.query().trim().split("\\s+")){if(!word.isBlank()){where.append(" AND a.title LIKE ?");args.add("%"+word+"%");}}
        if(query.supplementalOnly()) where.append(" AND (COALESCE(a.status,'') LIKE '%추가모집%' OR a.title LIKE '%추가모집%')");
        addAreaFilter(where,args,query.areaMin(),query.areaMax(),query.includeUnknown());
        addRentalFilter(where,args,query.depositMax(),query.rentMax(),query.includeUnknown());
        if(!query.includeUnknown()) addKnownFactsFilter(where,args);
        if(query.savedOnly()){
            if(query.savedIds().isEmpty())where.append(" AND 1=0");
            else {where.append(" AND a.id IN (").append(String.join(",",Collections.nCopies(query.savedIds().size(),"?"))).append(")");args.addAll(query.savedIds());}
        }
        addInterestFilter(where,args,query.interest());
        if(!query.includeClosed())where.append(" AND COALESCE((SELECT ends_at FROM announcement_events e WHERE e.announcement_id=a.id AND e.event_type='APPLICATION' ORDER BY ends_at LIMIT 1),a.application_end,'9999-12-31')>=date('now') AND COALESCE(a.status,'') NOT LIKE '%마감%' AND COALESCE(a.status,'') NOT LIKE '%종료%'");
        int total=jdbc.queryForObject("SELECT COUNT(*) FROM announcements a JOIN announcement_sources s ON s.id=a.source_id"+where,Integer.class,args.toArray());
        String order="DEADLINE".equals(query.sort())?" ORDER BY COALESCE((SELECT ends_at FROM announcement_events e WHERE e.announcement_id=a.id AND e.event_type='APPLICATION' ORDER BY ends_at LIMIT 1),a.application_end,'9999-12-31'),a.announcement_date DESC":" ORDER BY COALESCE(a.announcement_date,'') DESC,a.first_seen_at DESC";
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(query.size());pageArgs.add((query.page()-1)*query.size());
        List<String> ids=jdbc.queryForList("SELECT a.id FROM announcements a JOIN announcement_sources s ON s.id=a.source_id"+where+order+" LIMIT ? OFFSET ?",String.class,pageArgs.toArray());
        List<Map<String,Object>> items=new ArrayList<>();for(Map<String,Object> item:announcementsByIds(ids)){Map<String,Object> row=new LinkedHashMap<>(item);row.put("detail",detail(text(item.get("id"))));items.add(row);}
        Map<String,Object> applied=new LinkedHashMap<>();applied.put("region",query.region());applied.put("housingType",query.housingType());applied.put("status",query.status());applied.put("source",query.source());applied.put("query",query.query());applied.put("depositMaxWon",query.depositMax());applied.put("rentMaxWon",query.rentMax());applied.put("areaMin",query.areaMin());applied.put("areaMax",query.areaMax());applied.put("sort",query.sort());applied.put("includeClosed",query.includeClosed());applied.put("includeUnknown",query.includeUnknown());applied.put("savedOnly",query.savedOnly());applied.put("savedCount",query.savedIds().size());applied.put("interestOnly",query.interest().enabled());applied.put("supplementalOnly",query.supplementalOnly());
        return Map.of("items",items,"page",query.page(),"size",query.size(),"totalCount",total,"hasNext",query.page()*query.size()<total,"appliedFilters",applied);
    }

    public List<Map<String,Object>> home(int limit){return (List<Map<String,Object>>)page(new CatalogQuery(1,limit,"","","","","",null,null,null,null,"LATEST",false)).get("items");}
    public Map<String,Object> facets(){
        List<String> sources=jdbc.queryForList("SELECT DISTINCT s.name FROM announcements a JOIN announcement_sources s ON s.id=a.source_id WHERE a.active=1 AND a.content_kind='ANNOUNCEMENT' AND s.name IS NOT NULL ORDER BY s.name",String.class);
        List<String> statuses=jdbc.queryForList("SELECT DISTINCT status FROM announcements WHERE active=1 AND content_kind='ANNOUNCEMENT' AND status IS NOT NULL AND status<>'' ORDER BY status",String.class);
        List<String> housingTypes=jdbc.queryForList("SELECT DISTINCT housing_type FROM announcements WHERE active=1 AND content_kind='ANNOUNCEMENT' AND housing_type IS NOT NULL AND housing_type<>'' ORDER BY housing_type",String.class);
        Set<String> regions=new TreeSet<>(jdbc.queryForList("SELECT DISTINCT COALESCE(l.region2,l.region1) FROM announcement_sites ast JOIN housing_sites hs ON hs.id=ast.site_id JOIN locations l ON l.id=hs.location_id JOIN announcements a ON a.id=ast.announcement_id WHERE a.active=1 AND COALESCE(l.region2,l.region1) IS NOT NULL AND COALESCE(l.region2,l.region1)<>''",String.class));
        for(String raw:jdbc.queryForList("SELECT raw_summary_json FROM announcements WHERE active=1 AND raw_summary_json IS NOT NULL",String.class)) try {String area=mapper.readTree(raw).path("area").asText("").trim();if(hasText(area)&&!area.startsWith("목록 "))regions.add(area);}catch(Exception ignored){}
        List<Map<String,Object>> regionHierarchy=jdbc.query("SELECT DISTINCT l.region1,l.region2,l.region3 FROM announcement_sites ast JOIN housing_sites hs ON hs.id=ast.site_id JOIN locations l ON l.id=hs.location_id JOIN announcements a ON a.id=ast.announcement_id WHERE a.active=1 AND l.region1 IS NOT NULL AND l.region1<>'' ORDER BY l.region1,l.region2,l.region3",(rs,row)->{Map<String,Object> value=new LinkedHashMap<>();value.put("sido",rs.getString("region1"));value.put("sigungu",rs.getString("region2"));value.put("dong",rs.getString("region3"));return value;});
        return Map.of("sources",sources,"statuses",statuses,"housingTypes",housingTypes,"regions",List.copyOf(regions),"regionHierarchy",regionHierarchy);
    }

    public Map<String,Object> catalogSummary(){
        Map<String,Object> summary=new LinkedHashMap<>(catalogMetadata());
        summary.put("itemCount",jdbc.queryForObject("SELECT COUNT(*) FROM announcements WHERE active=1 AND content_kind='ANNOUNCEMENT'",Integer.class));
        return summary;
    }

    private Map<String,Object> catalogMetadata() {
        List<Map<String,Object>> rows=jdbc.query("SELECT finished_at,trigger_type FROM collection_runs WHERE finished_at IS NOT NULL AND status IN ('SUCCEEDED','SUCCEEDED_WITH_WARNINGS') ORDER BY finished_at DESC LIMIT 1",(rs,row)->Map.of("checkedAt",rs.getString("finished_at"),"mode",rs.getString("trigger_type").toLowerCase(Locale.ROOT)));
        if(!rows.isEmpty()) return rows.getFirst();
        Map<String,Object> empty=new LinkedHashMap<>(); empty.put("checkedAt",null); empty.put("mode",null); return empty;
    }

    public Map<String,Object> details() {
        Map<String,Object> items = new LinkedHashMap<>();
        for (String id : jdbc.queryForList("SELECT id FROM announcements WHERE detail_status IN ('PARSED','PARTIAL','NEEDS_REVIEW')", String.class)) {
            Map<String,Object> detail = detail(id);
            if (detail != null) items.put(id, detail);
        }
        return Map.of("schemaVersion", 2, "items", items);
    }

    public Map<String,Object> announcement(String id) {
        List<Map<String,Object>> rows = jdbc.query("SELECT * FROM announcements WHERE id=? AND active=1", (rs,row) -> mapAnnouncement(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Map<String,Object>> adminSearch(String query) {
        String term="%"+(query==null?"":query.trim())+"%";
        return jdbc.query("SELECT * FROM announcements WHERE active=1 AND (title LIKE ? OR official_url LIKE ?) ORDER BY COALESCE(announcement_date,'') DESC LIMIT 30",(rs,row)->mapAnnouncement(rs),term,term);
    }

    public Map<String,Object> detail(String id) {
        List<Map<String,Object>> base = jdbc.query("SELECT detail_status,official_url,last_changed_at FROM announcements WHERE id=?", (rs,row) -> {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("verificationStatus", switch (rs.getString(1)) { case "PARSED" -> "VERIFIED"; case "PARTIAL" -> "PARTIAL"; default -> "NEEDS_REVIEW"; });
            value.put("verifiedAt", rs.getString(3)); value.put("sourceUrl", rs.getString(2)); return value;
        }, id);
        if (base.isEmpty()) return null;
        Map<String,Object> detail = base.getFirst();
        List<Map<String,Object>> sites = sites(id);
        detail.put("sites", sites);
        List<Map<String,Object>> events=events(id);
        detail.put("events",events);
        detail.put("eligibilityRules",eligibilityRules(id));
        events.stream().filter(event->"APPLICATION".equals(event.get("eventType"))).findFirst().ifPresent(event->{
            Map<String,Object> application=new LinkedHashMap<>();
            application.put("start",event.get("startsAt")); application.put("end",event.get("endsAt")); application.put("status",event.get("status"));
            detail.put("application",application);
        });
        if (!sites.isEmpty()) applyLegacyProjection(detail, sites.getFirst());
        return detail;
    }

    public List<Map<String,Object>> search(String region, Double areaMin, Double areaMax, Long depositMax, Long rentMax) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT a.id FROM announcements a JOIN announcement_sites ast ON ast.announcement_id=a.id JOIN housing_sites hs ON hs.id=ast.site_id JOIN locations l ON l.id=hs.location_id JOIN supply_units u ON u.announcement_site_id=ast.id JOIN rental_options r ON r.supply_unit_id=u.id WHERE a.active=1");
        List<Object> args = new ArrayList<>();
        if (hasText(region)) { sql.append(" AND (l.region1 LIKE ? OR l.region2 LIKE ? OR l.region3 LIKE ? OR l.address LIKE ?)"); for (int i=0;i<4;i++) args.add("%"+region.trim()+"%"); }
        if (areaMin != null) { sql.append(" AND u.exclusive_area_m2>=?"); args.add(areaMin); }
        if (areaMax != null) { sql.append(" AND u.exclusive_area_m2<=?"); args.add(areaMax); }
        if (depositMax != null) { sql.append(" AND r.deposit_won<=?"); args.add(depositMax); }
        if (rentMax != null) { sql.append(" AND r.monthly_rent_won<=?"); args.add(rentMax); }
        List<String> ids = jdbc.queryForList(sql.toString(), String.class, args.toArray());
        return announcementsByIds(ids);
    }

    private List<Map<String,Object>> announcementsByIds(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String,Map<String,Object>> byId = new HashMap<>();
        jdbc.query("SELECT * FROM announcements WHERE active=1 AND id IN ("+placeholders+")", rs -> { Map<String,Object> item=mapAnnouncement(rs); byId.put(text(item.get("id")),item); }, ids.toArray());
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    public void exportCompatibilityJson() {
        write("all-announcements.json", catalog());
        write("announcement-details.json", details());
    }

    public void saveDetailHierarchy(String announcementId, String detailStatus, List<Map<String,Object>> sites, String at) {
        transactions.executeWithoutResult(status -> replaceDetailHierarchy(announcementId, detailStatus, sites, at, true));
    }

    public List<DetailCandidate> detailCandidates(String parserVersion, String at, int limit) {
        return jdbc.query("SELECT a.id,a.title,a.official_url,a.detail_attempt_count FROM announcements a JOIN announcement_sources s ON s.id=a.source_id " +
                        "WHERE a.active=1 AND s.kind='lh-list' AND (a.detail_last_attempt_at IS NULL OR COALESCE(a.detail_parser_version,'')<>? " +
                        "OR a.detail_last_attempt_at<a.last_changed_at OR (a.detail_attempt_status='FAILED' AND a.detail_next_retry_at IS NOT NULL AND a.detail_next_retry_at<=?)) " +
                        "ORDER BY CASE a.detail_status WHEN 'NOT_COLLECTED' THEN 0 WHEN 'NEEDS_REVIEW' THEN 1 ELSE 2 END,a.last_changed_at DESC LIMIT ?",
                (rs, row) -> new DetailCandidate(rs.getString("id"), rs.getString("title"), rs.getString("official_url"), rs.getInt("detail_attempt_count")),
                parserVersion, at, limit);
    }

    public void saveCollectedDetail(String announcementId, String detailStatus, List<Map<String,Object>> sites,
                                    String at, String parserVersion, String sourceHash, String documentUrl,
                                    String localPath) {
        transactions.executeWithoutResult(status -> {
            replaceDetailHierarchy(announcementId, detailStatus, sites, at, false);
            jdbc.update("UPDATE announcements SET detail_attempt_status='SUCCEEDED',detail_last_attempt_at=?,detail_next_retry_at=NULL," +
                            "detail_attempt_count=0,detail_error=NULL,detail_parser_version=?,detail_source_hash=? WHERE id=?",
                    at, parserVersion, sourceHash, announcementId);
            String documentId = sha(announcementId + ":" + documentUrl);
            jdbc.update("INSERT INTO announcement_documents(id,announcement_id,document_url,document_type,content_hash,local_path,retrieved_at,parser_version,parse_status,parse_error) " +
                            "VALUES(?,?,?,'DETAIL_PAGE',?,?,?,?,'PARSED',NULL) ON CONFLICT(id) DO UPDATE SET content_hash=excluded.content_hash," +
                            "local_path=excluded.local_path,retrieved_at=excluded.retrieved_at,parser_version=excluded.parser_version,parse_status='PARSED',parse_error=NULL",
                    documentId, announcementId, documentUrl, sourceHash, localPath, at, parserVersion);
        });
    }

    public void markDetailFailure(String announcementId, String at, String parserVersion, String error, String nextRetryAt) {
        jdbc.update("UPDATE announcements SET detail_status=CASE WHEN detail_status IN ('PARSED','PARTIAL') THEN detail_status ELSE 'NEEDS_REVIEW' END," +
                        "detail_attempt_status='FAILED',detail_last_attempt_at=?,detail_next_retry_at=?,detail_attempt_count=detail_attempt_count+1," +
                        "detail_error=?,detail_parser_version=? WHERE id=?",
                at, nextRetryAt, error, parserVersion, announcementId);
    }

    public void markDetailNotApplicable(String announcementId, String at, String parserVersion) {
        jdbc.update("UPDATE announcements SET detail_status=CASE WHEN detail_status IN ('PARSED','PARTIAL') THEN detail_status ELSE 'NOT_APPLICABLE' END," +
                        "detail_attempt_status='SKIPPED',detail_last_attempt_at=?,detail_next_retry_at=NULL,detail_error=NULL,detail_parser_version=? WHERE id=?",
                at, parserVersion, announcementId);
    }

    private void replaceDetailHierarchy(String announcementId, String detailStatus, List<Map<String,Object>> sites, String at, boolean updateChangedAt) {
            jdbc.update("DELETE FROM announcement_sites WHERE announcement_id=?", announcementId);
            for (int siteIndex=0; siteIndex<sites.size(); siteIndex++) {
                Map<String,Object> site=sites.get(siteIndex), location=map(site.get("location"));
                String sourceSiteKey=text(site.getOrDefault("sourceSiteKey", "site-"+siteIndex));
                String accuracy=text(location.getOrDefault("accuracy", "UNKNOWN")).toUpperCase(Locale.ROOT);
                boolean exactSharedKey="EXACT".equals(accuracy)&&hasText(site.get("canonicalKey"));
                String requestedCanonical=hasText(site.get("canonicalKey"))?text(site.get("canonicalKey")):sourceSiteKey;
                String canonical=exactSharedKey?requestedCanonical:announcementId+":"+requestedCanonical;
                String locationId=sha("location:"+canonical), siteId=sha("site:"+canonical), linkId=sha("announcement-site:"+announcementId+":"+sourceSiteKey);
                jdbc.update("INSERT INTO locations(id,canonical_key,region1,region2,region3,address,latitude,longitude,accuracy) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(canonical_key) DO UPDATE SET region1=excluded.region1,region2=excluded.region2,region3=excluded.region3,address=excluded.address,latitude=excluded.latitude,longitude=excluded.longitude,accuracy=excluded.accuracy",
                        locationId,canonical,nullable(location.get("region1")),nullable(location.get("region2")),nullable(location.get("region3")),nullable(location.get("address")),location.get("latitude"),location.get("longitude"),accuracy);
                jdbc.update("INSERT INTO housing_sites(id,canonical_key,name,location_id) VALUES(?,?,?,?) ON CONFLICT(canonical_key) DO UPDATE SET name=excluded.name,location_id=excluded.location_id",siteId,canonical,nullable(site.get("name")),locationId);
                jdbc.update("INSERT INTO announcement_sites(id,announcement_id,site_id,source_site_key,display_order,move_in_text,eligibility_text) VALUES(?,?,?,?,?,?,?)",linkId,announcementId,siteId,sourceSiteKey,siteIndex,nullable(site.get("moveInText")),nullable(site.get("eligibilityText")));
                List<Map<String,Object>> units=maps(site.get("units"));
                for(int unitIndex=0;unitIndex<units.size();unitIndex++){
                    Map<String,Object> unit=units.get(unitIndex); String unitKey=text(unit.getOrDefault("sourceUnitKey","unit-"+unitIndex)); String unitId=sha("unit:"+linkId+":"+unitKey);
                    jdbc.update("INSERT INTO supply_units(id,announcement_site_id,source_unit_key,unit_type,exclusive_area_m2,supply_count,category_text,floor_text,room_text,display_order) VALUES(?,?,?,?,?,?,?,?,?,?)",unitId,linkId,unitKey,nullable(unit.get("unitType")),unit.get("exclusiveAreaM2"),unit.get("supplyCount"),nullable(unit.get("categoryText")),nullable(unit.get("floorText")),nullable(unit.get("roomText")),unitIndex);
                    List<Map<String,Object>> options=maps(unit.get("rentalOptions"));
                    for(int optionIndex=0;optionIndex<options.size();optionIndex++){
                        Map<String,Object> option=options.get(optionIndex); String optionKey=text(option.getOrDefault("optionKey","option-"+optionIndex));
                        jdbc.update("INSERT INTO rental_options(id,supply_unit_id,option_key,condition_text,deposit_won,monthly_rent_won,management_fee_text,display_order) VALUES(?,?,?,?,?,?,?,?)",sha("option:"+unitId+":"+optionKey),unitId,optionKey,nullable(option.get("conditionText")),longOrNull(option.get("depositWon")),longOrNull(option.get("monthlyRentWon")),nullable(option.get("managementFeeText")),optionIndex);
                    }
                }
            }
            if (updateChangedAt) jdbc.update("UPDATE announcements SET detail_status=?,last_changed_at=? WHERE id=?",detailStatus,at,announcementId);
            else jdbc.update("UPDATE announcements SET detail_status=? WHERE id=?",detailStatus,announcementId);
    }

    private boolean upsertAnnouncement(Map<String,Object> item, boolean collected) {
        String id = text(item.get("id"));
        String sourceId = text(item.getOrDefault("sourceId", "legacy"));
        jdbc.update("INSERT OR IGNORE INTO announcement_sources(id,name,kind,active) VALUES(?,?, 'legacy',1)",
                sourceId, text(item.getOrDefault("sourceName", sourceId)));
        String providerId = provider(item.get("provider"));
        String raw = json(item), now = text(item.getOrDefault("retrievedAt", Instant.now().toString()));
        Map<String,Object> hashInput = new LinkedHashMap<>(item); hashInput.remove("retrievedAt");
        String hash = sha(json(hashInput));
        List<String> old = jdbc.queryForList("SELECT content_hash FROM announcements WHERE id=?", String.class, id);
        String date = nullable(item.getOrDefault("announcementDate", item.get("announcedAt")));
        if (old.isEmpty()) {
            jdbc.update("INSERT INTO announcements(id,source_id,source_announcement_key,provider_id,title,housing_type,announcement_date,application_start,application_end,status,official_url,detail_status,content_hash,first_seen_at,last_seen_at,last_changed_at,active,raw_summary_json,content_kind) VALUES(?,?,?,?,?,?,?,?,?,?,?,'NOT_COLLECTED',?,?,?,?,1,?,?)",
                    id, sourceId, text(item.getOrDefault("sourceAnnouncementKey", id)), providerId, text(item.getOrDefault("title", "제목 없음")), nullable(item.get("housingType")), date, nullable(item.get("applicationStart")), nullable(item.get("applicationEnd")), nullable(item.get("status")), text(item.getOrDefault("officialUrl", "")), hash, now, now, now, raw, contentKind(item));
            return true;
        }
        boolean changed = !old.getFirst().equals(hash);
        jdbc.update("UPDATE announcements SET provider_id=?,title=?,housing_type=?,announcement_date=?,application_start=?,application_end=?,status=?,official_url=?,content_hash=?,last_seen_at=?,last_changed_at=CASE WHEN content_hash<>? THEN ? ELSE last_changed_at END,active=1,raw_summary_json=?,content_kind=? WHERE id=?",
                providerId, text(item.getOrDefault("title", "제목 없음")), nullable(item.get("housingType")), date, nullable(item.get("applicationStart")), nullable(item.get("applicationEnd")), nullable(item.get("status")), text(item.getOrDefault("officialUrl", "")), hash, now, hash, now, raw, contentKind(item), id);
        return changed;
    }

    private Map<String,Object> mapAnnouncement(ResultSet rs) throws SQLException {
        Map<String,Object> item = jsonMap(rs.getString("raw_summary_json"));
        item.put("id", rs.getString("id")); item.put("sourceId", rs.getString("source_id"));
        item.put("title", rs.getString("title")); item.put("housingType", rs.getString("housing_type"));
        item.put("announcementDate", missing(rs.getString("announcement_date")));
        item.put("announcedAt", missing(rs.getString("announcement_date")));
        item.put("applicationStart", missing(rs.getString("application_start")));
        item.put("applicationEnd", missing(rs.getString("application_end")));
        item.put("status", missing(rs.getString("status"))); item.put("officialUrl", rs.getString("official_url"));
        item.put("retrievedAt", rs.getString("last_seen_at"));
        item.put("contentKind",rs.getString("content_kind"));
        return item;
    }

    private String contentKind(Map<String,Object> item){String title=text(item.get("title"));return title.matches(".*(?:당첨자|예비자).*(?:발표|명단).*|.*서류.*(?:제출|심사|안내).*|.*(?:계약|입주).*안내.*|.*결과.*발표.*")?"NOTICE":"ANNOUNCEMENT";}

    @SuppressWarnings("unchecked")
    private void importLegacyDetail(String announcementId, Map<String,Object> detail) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM announcements WHERE id=?", Integer.class, announcementId) == 0) return;
        Map<String,Object> location = map(detail.get("location"));
        String district = nullable(location.get("district")), address = nullable(location.get("address"));
        String canonical = announcementId + ":legacy-site", locationId = sha("location:" + canonical), siteId = sha("site:" + canonical), linkId = sha("announcement-site:" + canonical), unitId = sha("unit:" + canonical);
        String accuracy = address != null && address.contains("역") ? "LANDMARK" : address != null ? "EXACT" : district != null ? "DISTRICT" : "UNKNOWN";
        jdbc.update("INSERT OR IGNORE INTO locations(id,canonical_key,region2,address,accuracy) VALUES(?,?,?,?,?)", locationId, canonical, district, address, accuracy);
        jdbc.update("INSERT OR IGNORE INTO housing_sites(id,canonical_key,name,location_id) VALUES(?,?,?,?)", siteId, canonical, address != null ? address : district, locationId);
        jdbc.update("INSERT OR IGNORE INTO announcement_sites(id,announcement_id,site_id,source_site_key,display_order,move_in_text) VALUES(?,?,?,?,0,?)", linkId, announcementId, siteId, "legacy-site", nullable(detail.get("moveInText")));
        Double area = firstNumber(detail.get("exclusiveAreaText"));
        List<Map<String,Object>> options = maps(detail.get("costOptions"));
        String unitType = options.isEmpty() ? nullable(detail.get("exclusiveAreaText")) : nullable(options.getFirst().get("typeText"));
        jdbc.update("INSERT OR IGNORE INTO supply_units(id,announcement_site_id,source_unit_key,unit_type,exclusive_area_m2,display_order) VALUES(?,?,?,?,?,0)", unitId, linkId, "legacy-unit", unitType, area);
        for (int i=0;i<options.size();i++) {
            Map<String,Object> option = options.get(i);
            jdbc.update("INSERT OR IGNORE INTO rental_options(id,supply_unit_id,option_key,condition_text,deposit_won,monthly_rent_won,management_fee_text,display_order) VALUES(?,?,?,?,?,?,?,?)",
                    sha(unitId+":"+i), unitId, "legacy-option-"+i, nullable(option.get("conditionText")), longOrNull(option.get("depositWon")), longOrNull(option.get("monthlyRentWon")), nullable(map(detail.get("cost")).get("managementFeeText")), i);
        }
        jdbc.update("UPDATE announcements SET detail_status=?,application_start=COALESCE(?,application_start),application_end=COALESCE(?,application_end),last_changed_at=? WHERE id=?",
                "VERIFIED".equals(detail.get("verificationStatus")) ? "PARSED" : "PARTIAL", nullable(map(detail.get("application")).get("start")), nullable(map(detail.get("application")).get("end")), text(detail.getOrDefault("verifiedAt", Instant.now().toString())), announcementId);
        String sourceUrl = nullable(detail.get("sourceUrl"));
        if (sourceUrl != null) jdbc.update("INSERT OR IGNORE INTO announcement_documents(id,announcement_id,document_url,document_type,retrieved_at,parser_version,parse_status) VALUES(?,?,?,'DETAIL_PAGE',?,'legacy-import','PARSED')", sha(announcementId+":"+sourceUrl), announcementId, sourceUrl, detail.get("verifiedAt"));
    }

    private List<Map<String,Object>> sites(String announcementId) {
        List<Map<String,Object>> sites = jdbc.query("SELECT ast.id,ast.source_site_key,ast.move_in_text,ast.eligibility_text,hs.name,l.* FROM announcement_sites ast JOIN housing_sites hs ON hs.id=ast.site_id LEFT JOIN locations l ON l.id=hs.location_id WHERE ast.announcement_id=? ORDER BY ast.display_order", (rs,row)->{
            Map<String,Object> site=new LinkedHashMap<>(); site.put("id",rs.getString("id")); site.put("sourceSiteKey",rs.getString("source_site_key")); site.put("name",rs.getString("name")); site.put("moveInText",rs.getString("move_in_text")); site.put("eligibilityText",rs.getString("eligibility_text"));
            Map<String,Object> loc=new LinkedHashMap<>(); loc.put("region1",rs.getString("region1")); loc.put("region2",rs.getString("region2")); loc.put("region3",rs.getString("region3")); loc.put("address",rs.getString("address")); loc.put("latitude",rs.getObject("latitude")); loc.put("longitude",rs.getObject("longitude")); loc.put("accuracy",rs.getString("accuracy")); loc.put("displayLabel", firstText(rs.getString("address"),rs.getString("region3"),rs.getString("region2"),rs.getString("region1"))); site.put("location",loc); return site;
        }, announcementId);
        for (Map<String,Object> site : sites) { site.put("organizations",organizations(text(site.get("id")))); site.put("units", units(text(site.get("id")))); }
        return sites;
    }

    private List<Map<String,Object>> units(String siteLinkId) {
        List<Map<String,Object>> units=jdbc.query("SELECT * FROM supply_units WHERE announcement_site_id=? ORDER BY display_order",(rs,row)->{ Map<String,Object> unit=new LinkedHashMap<>(); unit.put("id",rs.getString("id")); unit.put("sourceUnitKey",rs.getString("source_unit_key")); unit.put("unitType",rs.getString("unit_type")); unit.put("exclusiveAreaM2",rs.getObject("exclusive_area_m2")); unit.put("supplyCount",rs.getObject("supply_count")); unit.put("categoryText",rs.getString("category_text")); unit.put("floorText",rs.getString("floor_text")); unit.put("roomText",rs.getString("room_text")); return unit; },siteLinkId);
        for(Map<String,Object> unit:units) { unit.put("rooms",rooms(text(unit.get("id")))); unit.put("rentalOptions",jdbc.query("SELECT * FROM rental_options WHERE supply_unit_id=? ORDER BY display_order",(rs,row)->{ Map<String,Object> option=new LinkedHashMap<>(); option.put("id",rs.getString("id")); option.put("conditionText",rs.getString("condition_text")); option.put("depositWon",rs.getObject("deposit_won")); option.put("monthlyRentWon",rs.getObject("monthly_rent_won")); option.put("managementFeeText",rs.getString("management_fee_text")); return option; },unit.get("id"))); }
        return units;
    }

    private List<Map<String,Object>> organizations(String siteId) { return jdbc.query("SELECT role,organization_name FROM site_organizations WHERE announcement_site_id=? ORDER BY role,organization_name",(rs,row)->Map.of("role",rs.getString("role"),"organizationName",rs.getString("organization_name")),siteId); }
    private List<Map<String,Object>> rooms(String unitId) { return jdbc.query("SELECT building_text,floor_text,room_text,source_text FROM supply_rooms WHERE supply_unit_id=? ORDER BY id",(rs,row)->{Map<String,Object> room=new LinkedHashMap<>();room.put("buildingText",rs.getString("building_text"));room.put("floorText",rs.getString("floor_text"));room.put("roomText",rs.getString("room_text"));room.put("sourceText",rs.getString("source_text"));return room;},unitId); }
    private List<Map<String,Object>> events(String announcementId) { return jdbc.query("SELECT event_type,starts_at,ends_at,status,description FROM announcement_events WHERE announcement_id=? ORDER BY COALESCE(starts_at,''),id",(rs,row)->{Map<String,Object> event=new LinkedHashMap<>();event.put("eventType",rs.getString("event_type"));event.put("startsAt",rs.getString("starts_at"));event.put("endsAt",rs.getString("ends_at"));event.put("status",rs.getString("status"));event.put("description",rs.getString("description"));return event;},announcementId); }
    private List<Map<String,Object>> eligibilityRules(String announcementId) { return jdbc.query("SELECT category,target_group,rank,household_size,operator,threshold_value,threshold_unit,description FROM eligibility_rules WHERE announcement_id=? ORDER BY category,COALESCE(rank,0),COALESCE(household_size,0),id",(rs,row)->{Map<String,Object> rule=new LinkedHashMap<>();rule.put("category",rs.getString("category"));rule.put("targetGroup",rs.getString("target_group"));rule.put("rank",rs.getObject("rank"));rule.put("householdSize",rs.getObject("household_size"));rule.put("operator",rs.getString("operator"));rule.put("thresholdValue",rs.getObject("threshold_value"));rule.put("thresholdUnit",rs.getString("threshold_unit"));rule.put("description",rs.getString("description"));return rule;},announcementId); }

    private void applyLegacyProjection(Map<String,Object> detail, Map<String,Object> site) {
        Map<String,Object> location=map(site.get("location"));
        detail.put("location",Map.of("district",Objects.toString(location.get("region2"),""),"address",Objects.toString(location.get("address"),"")));
        List<Map<String,Object>> units=maps(site.get("units"));
        if(units.isEmpty()) return;
        Map<String,Object> unit=units.getFirst(); Object area=unit.get("exclusiveAreaM2"); if(area!=null) detail.put("exclusiveAreaText",stripNumber(area)+"㎡");
        detail.put("moveInText",site.get("moveInText"));
        List<Map<String,Object>> options=maps(unit.get("rentalOptions"));
        List<Map<String,Object>> legacy=new ArrayList<>();
        for(Map<String,Object> option:options){ Map<String,Object> row=new LinkedHashMap<>(option); row.put("typeText",unit.get("unitType")); legacy.add(row); }
        detail.put("costOptions",legacy);
    }

    private void addAreaFilter(StringBuilder where,List<Object> args,Double minimum,Double maximum,boolean includeUnknown){
        if(minimum==null&&maximum==null)return;
        String known="SELECT 1 FROM announcement_sites ast JOIN supply_units u ON u.announcement_site_id=ast.id WHERE ast.announcement_id=a.id AND u.exclusive_area_m2 IS NOT NULL";
        StringBuilder matching=new StringBuilder(known);
        if(minimum!=null){matching.append(" AND u.exclusive_area_m2>=?");}
        if(maximum!=null){matching.append(" AND u.exclusive_area_m2<=?");}
        if(includeUnknown)where.append(" AND (NOT EXISTS (").append(known).append(") OR EXISTS (").append(matching).append("))");
        else where.append(" AND EXISTS (").append(matching).append(")");
        if(minimum!=null)args.add(minimum);
        if(maximum!=null)args.add(maximum);
    }

    private void addRentalFilter(StringBuilder where,List<Object> args,Long depositMaximum,Long rentMaximum,boolean includeUnknown){
        if(depositMaximum==null&&rentMaximum==null)return;
        String known="SELECT 1 FROM announcement_sites ast JOIN supply_units u ON u.announcement_site_id=ast.id JOIN rental_options r ON r.supply_unit_id=u.id WHERE ast.announcement_id=a.id AND r.deposit_won IS NOT NULL AND r.monthly_rent_won IS NOT NULL";
        StringBuilder matching=new StringBuilder(known);
        if(depositMaximum!=null)matching.append(" AND r.deposit_won<=?");
        if(rentMaximum!=null)matching.append(" AND r.monthly_rent_won<=?");
        if(includeUnknown)where.append(" AND (NOT EXISTS (").append(known).append(") OR EXISTS (").append(matching).append("))");
        else where.append(" AND EXISTS (").append(matching).append(")");
        if(depositMaximum!=null)args.add(depositMaximum);
        if(rentMaximum!=null)args.add(rentMaximum);
    }

    private void addKnownFactsFilter(StringBuilder where,List<Object> args){
        where.append(" AND (EXISTS (SELECT 1 FROM announcement_sites ast JOIN housing_sites hs ON hs.id=ast.site_id JOIN locations l ON l.id=hs.location_id WHERE ast.announcement_id=a.id AND COALESCE(NULLIF(l.address,''),NULLIF(l.region1,''),NULLIF(l.region2,''),NULLIF(l.region3,''),'')<>'') OR (a.raw_summary_json LIKE '%\\\"area\\\":\\\"%' AND a.raw_summary_json NOT LIKE '%목록 기재 없음%'))");
        where.append(" AND EXISTS (SELECT 1 FROM announcement_sites ast JOIN supply_units u ON u.announcement_site_id=ast.id WHERE ast.announcement_id=a.id AND u.exclusive_area_m2 IS NOT NULL)");
        where.append(" AND EXISTS (SELECT 1 FROM announcement_sites ast JOIN supply_units u ON u.announcement_site_id=ast.id JOIN rental_options r ON r.supply_unit_id=u.id WHERE ast.announcement_id=a.id AND r.deposit_won IS NOT NULL AND r.monthly_rent_won IS NOT NULL)");
    }

    private void addInterestFilter(StringBuilder where,List<Object> args,InterestQuery interest){
        if(!interest.enabled())return;
        if(interest.regions().isEmpty()&&interest.depositMax()==null&&interest.rentMax()==null&&interest.areaMin()==null&&interest.areaMax()==null){where.append(" AND 1=0");return;}
        if(!interest.regions().isEmpty()){
            where.append(" AND (");
            for(int index=0;index<interest.regions().size();index++){
                if(index>0)where.append(" OR ");
                where.append("EXISTS (SELECT 1 FROM announcement_sites ast JOIN housing_sites hs ON hs.id=ast.site_id JOIN locations l ON l.id=hs.location_id WHERE ast.announcement_id=a.id AND (l.region1 LIKE ? OR l.region2 LIKE ? OR l.region3 LIKE ? OR l.address LIKE ?)) OR a.raw_summary_json LIKE ?");
                String term="%"+interest.regions().get(index).trim()+"%";for(int count=0;count<5;count++)args.add(term);
            }
            if(interest.includeUnknown())where.append(" OR (NOT EXISTS (SELECT 1 FROM announcement_sites ast JOIN housing_sites hs ON hs.id=ast.site_id JOIN locations l ON l.id=hs.location_id WHERE ast.announcement_id=a.id AND COALESCE(NULLIF(l.address,''),NULLIF(l.region1,''),NULLIF(l.region2,''),NULLIF(l.region3,''),'')<>'') AND (a.raw_summary_json IS NULL OR a.raw_summary_json NOT LIKE '%\\\"area\\\":\\\"%'))");
            where.append(")");
        }
        addAreaFilter(where,args,interest.areaMin(),interest.areaMax(),interest.includeUnknown());
        addRentalFilter(where,args,interest.depositMax(),interest.rentMax(),interest.includeUnknown());
    }

    private void ensureSource(Map<String,Object> source) { jdbc.update("INSERT INTO announcement_sources(id,name,kind,active) VALUES(?,?,?,1) ON CONFLICT(id) DO UPDATE SET name=excluded.name,kind=excluded.kind,active=1", source.get("id"), source.getOrDefault("name",source.get("id")), source.getOrDefault("kind","legacy")); }
    private String provider(Object name) { if(!hasText(name)) return null; String display=text(name).trim(), normalized=display.toLowerCase(Locale.ROOT).replaceAll("\\s+"," "); String id=sha("provider:"+normalized); jdbc.update("INSERT OR IGNORE INTO providers(id,normalized_name,display_name) VALUES(?,?,?)",id,normalized,display); return id; }
    private Map<String,Object> read(String file,Map<String,Object> fallback){ try{ Path path=data.resolve(file); return Files.exists(path)?mapper.readValue(path.toFile(),new TypeReference<>(){}):fallback; }catch(Exception ignored){ return fallback; } }
    private void write(String file,Object value){ try{ Files.createDirectories(data); mapper.writerWithDefaultPrettyPrinter().writeValue(data.resolve(file).toFile(),value); }catch(Exception error){ throw new IllegalStateException(error); } }
    private String json(Object value){ try{return mapper.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);} }
    private Map<String,Object> jsonMap(String value){ try{return value==null?new LinkedHashMap<>():mapper.readValue(value,new TypeReference<>(){});}catch(Exception ignored){return new LinkedHashMap<>();} }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value){ return value instanceof Map<?,?> raw?copy(raw):new LinkedHashMap<>(); }
    private Map<String,Object> copy(Map<?,?> raw){ Map<String,Object> result=new LinkedHashMap<>(); raw.forEach((k,v)->result.put(String.valueOf(k),v)); return result; }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> maps(Object value){ if(!(value instanceof List<?> list)) return List.of(); return list.stream().filter(Map.class::isInstance).map(v->copy((Map<?,?>)v)).toList(); }
    private String text(Object value){ return value==null?"":String.valueOf(value); }
    private String nullable(Object value){ if(!hasText(value)||"목록 기재 없음".equals(String.valueOf(value))) return null; return String.valueOf(value); }
    private boolean hasText(Object value){ return value!=null&&!String.valueOf(value).isBlank(); }
    private String missing(String value){ return value==null?"목록 기재 없음":value; }
    private Long longOrNull(Object value){ return value instanceof Number n?n.longValue():null; }
    private Double firstNumber(Object value){ Matcher matcher=NUMBER.matcher(text(value)); return matcher.find()?Double.valueOf(matcher.group(1)):null; }
    private String firstText(String... values){ return Arrays.stream(values).filter(this::hasText).findFirst().orElse(""); }
    private String stripNumber(Object value){ double number=((Number)value).doubleValue(); return number==Math.rint(number)?Long.toString((long)number):Double.toString(number); }
    private String sha(String value){ try{ byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(bytes); }catch(Exception error){throw new IllegalStateException(error);} }
    public record SourceResult(List<Map<String,Object>> newItems,int changedCount) { public int newCount(){ return newItems.size(); } }
    public record DetailCandidate(String id, String title, String officialUrl, int attemptCount) {}
    public record InterestQuery(boolean enabled,List<String> regions,Long depositMax,Long rentMax,Double areaMin,Double areaMax,boolean includeUnknown) {
        public InterestQuery { regions=regions==null?List.of():regions.stream().filter(value->value!=null&&!value.isBlank()).distinct().toList(); }
    }
    public record CatalogQuery(int page,int size,String region,String housingType,String status,String source,String query,Long depositMax,Long rentMax,Double areaMin,Double areaMax,String sort,boolean includeClosed,boolean includeUnknown,boolean savedOnly,List<String> savedIds,InterestQuery interest,boolean supplementalOnly) {
        public CatalogQuery { savedIds=savedIds==null?List.of():savedIds.stream().filter(value->value!=null&&!value.isBlank()).distinct().toList();interest=interest==null?new InterestQuery(false,List.of(),null,null,null,null,true):interest; }
        public CatalogQuery(int page,int size,String region,String housingType,String status,String source,String query,Long depositMax,Long rentMax,Double areaMin,Double areaMax,String sort,boolean includeClosed,boolean includeUnknown,boolean savedOnly,List<String> savedIds,InterestQuery interest){this(page,size,region,housingType,status,source,query,depositMax,rentMax,areaMin,areaMax,sort,includeClosed,includeUnknown,savedOnly,savedIds,interest,false);}
        public CatalogQuery(int page,int size,String region,String housingType,String status,String source,String query,Long depositMax,Long rentMax,Double areaMin,Double areaMax,String sort,boolean includeClosed,boolean includeUnknown,List<String> savedIds,InterestQuery interest){this(page,size,region,housingType,status,source,query,depositMax,rentMax,areaMin,areaMax,sort,includeClosed,includeUnknown,savedIds!=null&&!savedIds.isEmpty(),savedIds,interest,false);}
        public CatalogQuery(int page,int size,String region,String housingType,String status,String source,String query,Long depositMax,Long rentMax,Double areaMin,Double areaMax,String sort,boolean includeClosed){this(page,size,region,housingType,status,source,query,depositMax,rentMax,areaMin,areaMax,sort,includeClosed,true,false,List.of(),null,false);}
    }
}
