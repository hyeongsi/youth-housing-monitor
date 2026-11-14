package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
class DocumentExtractionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    DocumentExtractionRepository(JdbcTemplate jdbc,ObjectMapper mapper,AnnouncementRepository ignored){ this.jdbc=jdbc; this.mapper=mapper; initialize(); }

    private void initialize(){
        for(String sql:List.of(
                "CREATE TABLE IF NOT EXISTS document_extractions (id TEXT PRIMARY KEY, announcement_document_id TEXT NOT NULL UNIQUE REFERENCES announcement_documents(id) ON DELETE CASCADE, parser_id TEXT NOT NULL, parser_version TEXT NOT NULL, site_code TEXT NOT NULL, document_type TEXT NOT NULL, status TEXT NOT NULL, extracted_json TEXT NOT NULL, validation_json TEXT NOT NULL, created_at TEXT NOT NULL, reviewed_at TEXT, committed_at TEXT)",
                "CREATE TABLE IF NOT EXISTS extraction_evidence (id TEXT PRIMARY KEY, extraction_id TEXT NOT NULL REFERENCES document_extractions(id) ON DELETE CASCADE, field_path TEXT NOT NULL, page_number INTEGER NOT NULL, bounding_box_json TEXT NOT NULL, raw_text TEXT NOT NULL, method TEXT NOT NULL, review_status TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS supply_rooms (id TEXT PRIMARY KEY, supply_unit_id TEXT NOT NULL REFERENCES supply_units(id) ON DELETE CASCADE, building_text TEXT, floor_text TEXT, room_text TEXT, source_text TEXT)",
                "CREATE TABLE IF NOT EXISTS site_organizations (id TEXT PRIMARY KEY, announcement_site_id TEXT NOT NULL REFERENCES announcement_sites(id) ON DELETE CASCADE, role TEXT NOT NULL, organization_name TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS announcement_events (id TEXT PRIMARY KEY, announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE, event_type TEXT NOT NULL, starts_at TEXT, ends_at TEXT, status TEXT NOT NULL, description TEXT)",
                "CREATE TABLE IF NOT EXISTS eligibility_rules (id TEXT PRIMARY KEY, announcement_id TEXT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE, category TEXT NOT NULL, target_group TEXT, rank INTEGER, household_size INTEGER, operator TEXT, threshold_value NUMERIC, threshold_unit TEXT, description TEXT)",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_committed_document_hash ON announcement_documents(announcement_id,content_hash) WHERE parse_status='COMMITTED'",
                "CREATE INDEX IF NOT EXISTS idx_extractions_status ON document_extractions(status,created_at)"
        )) jdbc.execute(sql);
    }

    String create(String extractionId,String documentId,String announcementId,String siteCode,String documentType,String hash,String relativePath,
                  String parserId,Map<String,Object> draft,List<DocumentParser.Evidence> evidence,String status){
        String now=Instant.now().toString();
        jdbc.update("INSERT INTO announcement_documents(id,announcement_id,document_url,document_type,content_hash,local_path,retrieved_at,parser_version,parse_status) VALUES(?,?,?,?,?,?,?,?,?)",
                documentId,announcementId,"/api/admin/document-imports/"+extractionId+"/document",documentType,hash,relativePath,now,parserId,status);
        jdbc.update("INSERT INTO document_extractions(id,announcement_document_id,parser_id,parser_version,site_code,document_type,status,extracted_json,validation_json,created_at) VALUES(?,?,?,?,?,?,?,?,'{}',?)",
                extractionId,documentId,parserId,"1",siteCode,documentType,status,json(draft),now);
        for(DocumentParser.Evidence item:evidence) jdbc.update("INSERT INTO extraction_evidence(id,extraction_id,field_path,page_number,bounding_box_json,raw_text,method,review_status) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),extractionId,item.fieldPath(),item.pageNumber(),json(item.boundingBox()),item.rawText(),item.method(),item.reviewStatus());
        return extractionId;
    }

    Map<String,Object> find(String id){
        List<Map<String,Object>> rows=jdbc.query("SELECT e.*,d.announcement_id,d.content_hash,d.local_path,d.parse_status FROM document_extractions e JOIN announcement_documents d ON d.id=e.announcement_document_id WHERE e.id=?",(rs,row)->{
            Map<String,Object> value=new LinkedHashMap<>(); value.put("id",rs.getString("id")); value.put("announcementId",rs.getString("announcement_id")); value.put("siteCode",rs.getString("site_code")); value.put("documentType",rs.getString("document_type")); value.put("parserId",rs.getString("parser_id")); value.put("status",rs.getString("status")); value.put("draft",map(rs.getString("extracted_json"))); value.put("validation",map(rs.getString("validation_json"))); value.put("contentHash",rs.getString("content_hash")); value.put("localPath",rs.getString("local_path")); value.put("createdAt",rs.getString("created_at")); value.put("reviewedAt",rs.getString("reviewed_at")); value.put("committedAt",rs.getString("committed_at")); return value;
        },id);
        if(rows.isEmpty()) return null;
        Map<String,Object> result=rows.getFirst();
        result.put("evidence",jdbc.query("SELECT id,field_path,page_number,bounding_box_json,raw_text,method,review_status FROM extraction_evidence WHERE extraction_id=? ORDER BY page_number,id",(rs,row)->{Map<String,Object> item=new LinkedHashMap<>();item.put("id",rs.getString(1));item.put("fieldPath",rs.getString(2));item.put("pageNumber",rs.getInt(3));item.put("boundingBox",map(rs.getString(4)));item.put("rawText",rs.getString(5));item.put("method",rs.getString(6));item.put("reviewStatus",rs.getString(7));return item;},id));
        return result;
    }
    Map<String,Object> list(String status,String query,int page,int size){
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(!status.isBlank()){where+=" AND e.status=?";args.add(status);}
        if(!query.isBlank()){where+=" AND (a.title LIKE ? OR e.site_code LIKE ? OR e.document_type LIKE ?)";for(int i=0;i<3;i++)args.add("%"+query.trim()+"%");}
        int total=jdbc.queryForObject("SELECT COUNT(*) FROM document_extractions e JOIN announcement_documents d ON d.id=e.announcement_document_id JOIN announcements a ON a.id=d.announcement_id"+where,Integer.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
        List<Map<String,Object>> items=jdbc.query("SELECT e.id,e.site_code,e.document_type,e.status,e.parser_id,e.created_at,a.title,COUNT(CASE WHEN v.review_status='NEEDS_REVIEW' THEN 1 END) AS review_count FROM document_extractions e JOIN announcement_documents d ON d.id=e.announcement_document_id JOIN announcements a ON a.id=d.announcement_id LEFT JOIN extraction_evidence v ON v.extraction_id=e.id"+where+" GROUP BY e.id ORDER BY e.created_at DESC LIMIT ? OFFSET ?",(rs,row)->Map.of("id",rs.getString("id"),"siteCode",rs.getString("site_code"),"documentType",rs.getString("document_type"),"status",rs.getString("status"),"parserId",rs.getString("parser_id"),"createdAt",rs.getString("created_at"),"title",rs.getString("title"),"needsReviewCount",rs.getInt("review_count")),pageArgs.toArray());
        return Map.of("items",items,"page",page,"size",size,"totalCount",total,"hasNext",page*size<total);
    }
    List<Map<String,Object>> workItemRows(String query){
        String where=" WHERE a.active=1 AND a.content_kind='ANNOUNCEMENT'";List<Object> args=new ArrayList<>();
        if(!query.isBlank()){where+=" AND (a.title LIKE ? OR s.name LIKE ? OR a.housing_type LIKE ?)";for(int i=0;i<3;i++)args.add("%"+query.trim()+"%");}
        return jdbc.query("SELECT a.id,a.title,a.housing_type,a.status,a.announcement_date,a.official_url,a.source_id,s.name AS source_name,a.detail_status,"+
                        "(SELECT e.id FROM document_extractions e JOIN announcement_documents d ON d.id=e.announcement_document_id WHERE d.announcement_id=a.id ORDER BY e.created_at DESC LIMIT 1) AS extraction_id,"+
                        "(SELECT e.status FROM document_extractions e JOIN announcement_documents d ON d.id=e.announcement_document_id WHERE d.announcement_id=a.id ORDER BY e.created_at DESC LIMIT 1) AS extraction_status,"+
                        "(SELECT COUNT(*) FROM extraction_evidence v WHERE v.extraction_id=(SELECT e.id FROM document_extractions e JOIN announcement_documents d ON d.id=e.announcement_document_id WHERE d.announcement_id=a.id ORDER BY e.created_at DESC LIMIT 1) AND v.review_status NOT IN ('CONFIRMED','MANUALLY_REVIEWED')) AS unreviewed_count,"+
                        "EXISTS(SELECT 1 FROM announcement_sites ast WHERE ast.announcement_id=a.id) AS has_detail " +
                        "FROM announcements a JOIN announcement_sources s ON s.id=a.source_id"+where+" ORDER BY COALESCE(a.announcement_date,'') DESC,a.first_seen_at DESC",
                (rs,row)->{Map<String,Object> value=new LinkedHashMap<>();value.put("id",rs.getString("id"));value.put("title",rs.getString("title"));value.put("housingType",rs.getString("housing_type"));value.put("status",rs.getString("status"));value.put("announcementDate",rs.getString("announcement_date"));value.put("officialUrl",rs.getString("official_url"));value.put("sourceId",rs.getString("source_id"));value.put("sourceName",rs.getString("source_name"));value.put("detailStatus",rs.getString("detail_status"));value.put("extractionId",rs.getString("extraction_id"));value.put("extractionStatus",rs.getString("extraction_status"));value.put("unreviewedCount",rs.getInt("unreviewed_count"));value.put("hasDetail",rs.getBoolean("has_detail"));return value;},args.toArray());
    }
    void updateDraft(String id,Map<String,Object> draft){ jdbc.update("UPDATE document_extractions SET extracted_json=?,status='DRAFT',reviewed_at=? WHERE id=?",json(draft),Instant.now().toString(),id); }
    void invalidateAutomaticEvidence(String extractionId,Collection<String> changedPaths){
        if(changedPaths.isEmpty())return;
        for(Map<String,Object> evidence:jdbc.query("SELECT id,field_path,method,review_status FROM extraction_evidence WHERE extraction_id=?",(rs,row)->Map.<String,Object>of("id",rs.getString(1),"fieldPath",rs.getString(2),"method",rs.getString(3),"reviewStatus",rs.getString(4)),extractionId)){
            String path=text(evidence.get("fieldPath"));
            boolean related=changedPaths.stream().anyMatch(changed->relatedPath(path,changed));
            if(related&&!("MANUAL".equals(evidence.get("method"))&&"MANUALLY_REVIEWED".equals(evidence.get("reviewStatus")))) jdbc.update("UPDATE extraction_evidence SET review_status='NEEDS_REVIEW' WHERE id=? AND extraction_id=?",evidence.get("id"),extractionId);
        }
    }
    void updateEvidenceReviews(String extractionId,List<Map<String,Object>> reviews){
        Set<String> allowed=Set.of("MATCHED","NEEDS_REVIEW","CONFIRMED","MANUALLY_REVIEWED");
        for(Map<String,Object> review:reviews){String evidenceId=text(review.get("id")),status=text(review.get("reviewStatus")),fieldPath=text(review.get("fieldPath"));if(Boolean.TRUE.equals(review.get("deleted"))&&!evidenceId.isBlank()){jdbc.update("DELETE FROM extraction_evidence WHERE id=? AND extraction_id=?",evidenceId,extractionId);continue;}if(!allowed.contains(status)||fieldPath.isBlank())continue;if(evidenceId.isBlank()){jdbc.update("INSERT INTO extraction_evidence(id,extraction_id,field_path,page_number,bounding_box_json,raw_text,method,review_status) VALUES(?,?,?,?,? ,?,'MANUAL',?)",UUID.randomUUID().toString(),extractionId,fieldPath,Math.max(1,review.get("pageNumber") instanceof Number number?number.intValue():1),"{}",text(review.getOrDefault("rawText","사용자 직접 입력")),status);continue;}if("MANUALLY_REVIEWED".equals(status))jdbc.update("UPDATE extraction_evidence SET field_path=?,review_status=?,method='MANUAL' WHERE id=? AND extraction_id=?",fieldPath,status,evidenceId,extractionId);else jdbc.update("UPDATE extraction_evidence SET field_path=?,review_status=? WHERE id=? AND extraction_id=?",fieldPath,status,evidenceId,extractionId);}
    }
    void saveValidation(String id,Map<String,Object> validation,String status){ jdbc.update("UPDATE document_extractions SET validation_json=?,status=?,reviewed_at=? WHERE id=?",json(validation),status,Instant.now().toString(),id); }
    void markCommitted(String id){ String now=Instant.now().toString(); jdbc.update("UPDATE document_extractions SET status='COMMITTED',committed_at=? WHERE id=?",now,id); jdbc.update("UPDATE announcement_documents SET parse_status='COMMITTED' WHERE id=(SELECT announcement_document_id FROM document_extractions WHERE id=?)",id); }
    String extractionIdForHash(String announcementId,String hash){List<String> ids=jdbc.queryForList("SELECT e.id FROM document_extractions e JOIN announcement_documents d ON d.id=e.announcement_document_id WHERE d.announcement_id=? AND d.content_hash=? ORDER BY e.created_at DESC LIMIT 1",String.class,announcementId,hash);return ids.isEmpty()?null:ids.getFirst();}
    boolean committedHash(String announcementId,String hash,String exceptId){ return jdbc.queryForObject("SELECT COUNT(*) FROM announcement_documents d LEFT JOIN document_extractions e ON e.announcement_document_id=d.id WHERE d.announcement_id=? AND d.content_hash=? AND d.parse_status='COMMITTED' AND (e.id IS NULL OR e.id<>?)",Integer.class,announcementId,hash,exceptId)>0; }
    String documentPath(String id){ List<String> paths=jdbc.queryForList("SELECT d.local_path FROM document_extractions e JOIN announcement_documents d ON d.id=e.announcement_document_id WHERE e.id=?",String.class,id); return paths.isEmpty()?null:paths.getFirst(); }
    String recordRevision(String announcementId,String action,String extractionId,String documentHash,String sourceRevisionId,Map<String,Object> before,Map<String,Object> after,String at){
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO announcement_detail_revisions(id,announcement_id,action,extraction_id,document_hash,source_revision_id,before_json,after_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,announcementId,action,nullable(extractionId),nullable(documentHash),nullable(sourceRevisionId),json(before),json(after),at);
        return id;
    }

    void recordChangeEvents(String announcementId,String revisionId,Map<String,Object> before,Map<String,Object> after,String at){
        if(!hasDetail(before)) return;
        for(Map<String,Object> change:DetailRevisionDiff.compare(before,after)){
            String category=changeCategory(change);
            if(category==null) continue;
            String fieldPath=String.valueOf(change.get("label"));
            jdbc.update("INSERT OR IGNORE INTO announcement_change_events(id,announcement_id,detail_revision_id,category,field_path,before_json,after_json,occurred_at,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),announcementId,revisionId,category,fieldPath,json(change.get("beforeRaw")),json(change.get("afterRaw")),at,at);
        }
    }

    private boolean hasDetail(Map<String,Object> detail){return !maps(detail.get("sites")).isEmpty()||!maps(detail.get("events")).isEmpty()||!maps(detail.get("eligibilityRules")).isEmpty();}
    private String changeCategory(Map<String,Object> change){
        String section=text(change.get("section")),field=text(change.get("field"));
        if("임대 조건".equals(section)&&Set.of("보증금","월세","관리비","전환 조건").contains(field)) return "RENTAL_CONDITION";
        if("일정".equals(section)&&Set.of("시작","종료","상태").contains(field)) return "SCHEDULE";
        if("공급 주택".equals(section)&&Set.of("단지명","주소","공급호수","전용면적","호실").contains(field)) return "HOUSING";
        return null;
    }
    List<Map<String,Object>> revisions(String announcementId){
        return jdbc.query("SELECT id,action,document_hash,source_revision_id,before_json,after_json,created_at FROM announcement_detail_revisions WHERE announcement_id=? ORDER BY created_at DESC,id DESC",(rs,row)->{
            Map<String,Object> value=new LinkedHashMap<>();value.put("id",rs.getString("id"));value.put("action",rs.getString("action"));value.put("documentHash",rs.getString("document_hash"));value.put("sourceRevisionId",rs.getString("source_revision_id"));value.put("before",summary(map(rs.getString("before_json"))));value.put("after",summary(map(rs.getString("after_json"))));value.put("createdAt",rs.getString("created_at"));return value;
        },announcementId);
    }
    Map<String,Object> revision(String announcementId,String revisionId){
        List<Map<String,Object>> rows=jdbc.query("SELECT id,action,extraction_id,document_hash,source_revision_id,before_json,after_json,created_at FROM announcement_detail_revisions WHERE id=? AND announcement_id=?",(rs,row)->{
            Map<String,Object> value=new LinkedHashMap<>();value.put("id",rs.getString("id"));value.put("action",rs.getString("action"));value.put("extractionId",rs.getString("extraction_id"));value.put("documentHash",rs.getString("document_hash"));value.put("sourceRevisionId",rs.getString("source_revision_id"));value.put("before",map(rs.getString("before_json")));value.put("after",map(rs.getString("after_json")));value.put("createdAt",rs.getString("created_at"));return value;
        },revisionId,announcementId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    void replaceOperationalExtras(String announcementId,Map<String,Object> draft){
        jdbc.update("DELETE FROM announcement_events WHERE announcement_id=?",announcementId); jdbc.update("DELETE FROM eligibility_rules WHERE announcement_id=?",announcementId);
        List<Map<String,Object>> sites=maps(draft.get("sites"));
        for(int si=0;si<sites.size();si++){
            Map<String,Object> site=sites.get(si); List<String> siteIds=jdbc.queryForList("SELECT id FROM announcement_sites WHERE announcement_id=? AND source_site_key=?",String.class,announcementId,text(site.getOrDefault("sourceSiteKey","site-"+si))); if(siteIds.isEmpty()) continue; String siteId=siteIds.getFirst();
            jdbc.update("DELETE FROM site_organizations WHERE announcement_site_id=?",siteId);
            for(Map<String,Object> organization:maps(site.get("organizations"))) jdbc.update("INSERT INTO site_organizations(id,announcement_site_id,role,organization_name) VALUES(?,?,?,?)",UUID.randomUUID().toString(),siteId,text(organization.get("role")),text(organization.get("organizationName")));
            List<Map<String,Object>> units=maps(site.get("units"));
            for(int ui=0;ui<units.size();ui++){ Map<String,Object> unit=units.get(ui); List<String> unitIds=jdbc.queryForList("SELECT id FROM supply_units WHERE announcement_site_id=? AND source_unit_key=?",String.class,siteId,text(unit.getOrDefault("sourceUnitKey","unit-"+ui))); if(unitIds.isEmpty()) continue; String unitId=unitIds.getFirst(); jdbc.update("DELETE FROM supply_rooms WHERE supply_unit_id=?",unitId); for(Map<String,Object> room:maps(unit.get("rooms"))) jdbc.update("INSERT INTO supply_rooms(id,supply_unit_id,building_text,floor_text,room_text,source_text) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),unitId,text(room.get("buildingText")),text(room.get("floorText")),text(room.get("roomText")),text(room.get("sourceText"))); }
        }
        for(Map<String,Object> event:maps(draft.get("events"))) jdbc.update("INSERT INTO announcement_events(id,announcement_id,event_type,starts_at,ends_at,status,description) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),announcementId,text(event.get("eventType")),nullable(event.get("startsAt")),nullable(event.get("endsAt")),text(event.getOrDefault("status","CONFIRMED")),nullable(event.get("description")));
        for(Map<String,Object> rule:maps(draft.get("eligibilityRules"))) jdbc.update("INSERT INTO eligibility_rules(id,announcement_id,category,target_group,rank,household_size,operator,threshold_value,threshold_unit,description) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),announcementId,text(rule.get("category")),nullable(rule.get("targetGroup")),rule.get("rank"),rule.get("householdSize"),nullable(rule.get("operator")),rule.get("thresholdValue"),nullable(rule.get("thresholdUnit")),nullable(rule.get("description")));
    }

    private Map<String,Object> summary(Map<String,Object> snapshot){
        int sites=maps(snapshot.get("sites")).size(), units=0, options=0;
        for(Map<String,Object> site:maps(snapshot.get("sites"))) for(Map<String,Object> unit:maps(site.get("units"))){units++;options+=maps(unit.get("rentalOptions")).size();}
        Map<String,Object> value=new LinkedHashMap<>();value.put("sites",sites);value.put("units",units);value.put("rentalOptions",options);value.put("events",maps(snapshot.get("events")).size());value.put("eligibilityRules",maps(snapshot.get("eligibilityRules")).size());return value;
    }

    private String json(Object value){ try{return mapper.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);} }
    private boolean relatedPath(String evidencePath,String changedPath){return evidencePath.equals(changedPath)||evidencePath.startsWith(changedPath+".")||evidencePath.startsWith(changedPath+"[")||changedPath.startsWith(evidencePath+".")||changedPath.startsWith(evidencePath+"[");}
    private Map<String,Object> map(String value){ try{return mapper.readValue(value,new TypeReference<>(){});}catch(Exception error){return new LinkedHashMap<>();} }
    private List<Map<String,Object>> maps(Object value){ if(!(value instanceof List<?> list)) return List.of(); List<Map<String,Object>> result=new ArrayList<>(); for(Object item:list) if(item instanceof Map<?,?> raw){Map<String,Object> copy=new LinkedHashMap<>();raw.forEach((key,itemValue)->copy.put(String.valueOf(key),itemValue));result.add(copy);} return result; }
    private String text(Object value){return value==null?"":String.valueOf(value);} private String nullable(Object value){return value==null||String.valueOf(value).isBlank()?null:String.valueOf(value);}
}
