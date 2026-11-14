package kr.seoulhousing.monitor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
class DocumentImportService {
    private static final long MAX_BYTES=20L*1024*1024;
    private final AnnouncementRepository announcements; private final DocumentExtractionRepository extractions;
    private final PdfTextLayoutExtractor extractor; private final DocumentParserRegistry parsers; private final TransactionTemplate transactions;
    private final Path root; private final Path storage;
    DocumentImportService(AnnouncementRepository announcements,DocumentExtractionRepository extractions,PdfTextLayoutExtractor extractor,
                          DocumentParserRegistry parsers,PlatformTransactionManager transactionManager,@Value("${housing.root}") String root){
        this.announcements=announcements;this.extractions=extractions;this.extractor=extractor;this.parsers=parsers;this.transactions=new TransactionTemplate(transactionManager);
        this.root=Path.of(root).normalize();this.storage=this.root.resolve("data/document-imports").normalize();
    }

    Map<String,Object> create(String siteCode,String documentType,String announcementId,MultipartFile file){
        DocumentParser parser=parsers.find(siteCode,documentType).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"지원하지 않는 기관 또는 문서 유형입니다."));
        Map<String,Object> announcement=announcements.announcement(announcementId); if(announcement==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"연결할 기존 공고를 선택해 주세요.");
        byte[] bytes; try{bytes=file.getBytes();}catch(Exception error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PDF를 읽지 못했습니다.",error);}
        if(bytes.length==0||bytes.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"PDF는 20MB 이하여야 합니다.");
        String contentType=Objects.toString(file.getContentType(),""); if(!contentType.isBlank()&&!"application/pdf".equalsIgnoreCase(contentType)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PDF MIME 형식만 허용합니다.");
        if(bytes.length<5||bytes[0]!='%'||bytes[1]!='P'||bytes[2]!='D'||bytes[3]!='F'||bytes[4]!='-') throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PDF signature가 올바르지 않습니다.");
        String hash=sha(bytes); String existing=extractions.extractionIdForHash(announcementId,hash);if(existing!=null)return Map.of("duplicate",true,"existingExtractionId",existing,"announcementId",announcementId);
        PdfTextLayoutExtractor.DocumentLayout layout; try{layout=extractor.extract(bytes);}catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}catch(Exception error){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"PDF 분석에 실패했습니다.",error);}
        DocumentParser.ParseResult parsed=parser.parse(layout,new DocumentParserRegistry.Key(siteCode,documentType)); String id=UUID.randomUUID().toString(); String documentId=UUID.randomUUID().toString();
        Path directory=storage.resolve(id).normalize(); Path target=directory.resolve(hash+".pdf").normalize(); if(!target.startsWith(storage)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"잘못된 저장 경로입니다.");
        try{Files.createDirectories(directory);Path temporary=Files.createTempFile(directory,"upload-",".tmp");Files.write(temporary,bytes);Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception error){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"PDF 저장에 실패했습니다.",error);}
        Map<String,Object> draft=new LinkedHashMap<>(parsed.draft()); draft.put("warnings",parsed.warnings()); draft.put("pageCount",layout.pageCount()); draft.put("hasTextLayer",layout.hasTextLayer());
        addHtmlConflictWarnings(draft,announcement);
        String relative=root.relativize(target).toString().replace('\\','/');
        try{extractions.create(id,documentId,announcementId,siteCode,documentType,hash,relative,parser.parserId(),draft,parsed.evidence(),parsed.supported()?"DRAFT":"FAILED");}
        catch(RuntimeException error){try{Files.deleteIfExists(target);}catch(Exception ignored){} throw error;}
        return get(id);
    }

    Map<String,Object> get(String id){Map<String,Object> value=extractions.find(id);if(value==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"분석 초안을 찾을 수 없습니다.");return value;}
    Map<String,Object> list(String status,String query,int page,int size){return extractions.list(status,query,page,size);}
    Map<String,Object> workItems(String status,String query,int page,int size){
        List<Map<String,Object>> all=extractions.workItemRows(query).stream().map(this::workItem).toList();
        Map<String,Long> counts=new LinkedHashMap<>();for(String key:List.of("REVIEW_REQUIRED","READY_TO_PUBLISH","PDF_REQUIRED","COMPLETED","DETAIL_AVAILABLE","PDF_UNSUPPORTED","ANALYSIS_FAILED"))counts.put(key,all.stream().filter(item->key.equals(item.get("workStatus"))).count());
        List<Map<String,Object>> filtered=status.isBlank()||"ALL".equals(status)?all:all.stream().filter(item->status.equals(item.get("workStatus"))).toList();
        int start=Math.min((page-1)*size,filtered.size()),end=Math.min(start+size,filtered.size());
        return Map.of("items",filtered.subList(start,end),"page",page,"size",size,"totalCount",filtered.size(),"hasNext",end<filtered.size(),"counts",counts);
    }
    Map<String,Object> update(String id,Map<String,Object> payload){Map<String,Object> current=get(id);if("COMMITTED".equals(current.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 확정된 초안입니다.");Map<String,Object> draft=payload.containsKey("draft")?map(payload.get("draft")):payload;List<String> changedPaths=changedPaths(map(current.get("draft")),draft,"");extractions.updateDraft(id,draft);if(payload.get("evidenceReviews") instanceof List<?>)extractions.updateEvidenceReviews(id,maps(payload.get("evidenceReviews")));extractions.invalidateAutomaticEvidence(id,changedPaths);return get(id);}
    Map<String,Object> validate(String id){Map<String,Object> current=get(id);Map<String,Object> validation=validateDraft(current);String status=((List<?>)validation.get("errors")).isEmpty()?"READY":"DRAFT";extractions.saveValidation(id,validation,status);return validation;}
    Map<String,Object> commit(String id){
        Map<String,Object> current=get(id);Map<String,Object> validation=validateDraft(current);List<?> errors=(List<?>)validation.get("errors");extractions.saveValidation(id,validation,errors.isEmpty()?"READY":"DRAFT");
        if(!errors.isEmpty())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"검증 오류를 수정한 뒤 확정해 주세요.");
        String announcementId=String.valueOf(current.get("announcementId"));Map<String,Object> draft=map(current.get("draft"));
        try{transactions.executeWithoutResult(status->{Map<String,Object> before=snapshot(announcementId);String now=Instant.now().toString();announcements.saveDetailHierarchy(announcementId,"PARSED",normalizedSites(draft),now);extractions.replaceOperationalExtras(announcementId,draft);extractions.markCommitted(id);Map<String,Object> after=snapshot(announcementId);String revisionId=extractions.recordRevision(announcementId,"PDF_COMMITTED",id,String.valueOf(current.get("contentHash")),null,before,after,now);extractions.recordChangeEvents(announcementId,revisionId,before,after,now);});}
        catch(RuntimeException error){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"확정 저장에 실패했습니다. 초안은 유지됩니다.",error);}
        return get(id);
    }
    Path document(String id){String relative=extractions.documentPath(id);if(relative==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"PDF를 찾을 수 없습니다.");Path path=root.resolve(relative).normalize();if(!path.startsWith(storage)||!Files.isRegularFile(path))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"PDF를 찾을 수 없습니다.");return path;}
    List<Map<String,Object>> announcements(String query){return announcements.adminSearch(query);}
    Map<String,Object> announcement(String id){Map<String,Object> item=announcements.announcement(id);if(item==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"공고를 찾을 수 없습니다.");return item;}
    List<Map<String,Object>> parserOptions(String announcementId){
        Map<String,Object> item=announcement(announcementId);String source=Objects.toString(item.get("sourceId"),"");String housing=Objects.toString(item.get("housingType"),"");String title=Objects.toString(item.get("title"),"");
        List<DocumentParserRegistry.Key> keys=parsers.keys().stream().filter(key->{if("seoul-youth-safe".equals(source))return "SEOUL_YOUTH_SAFE".equals(key.siteCode())&&title.contains("민간임대");if("lh-rental-list".equals(source))return "LH".equals(key.siteCode())&&((housing.contains("행복주택")&&"HAPPY_HOUSING_NOTICE".equals(key.documentType()))||(housing.contains("국민임대")&&"NATIONAL_RENTAL_NOTICE".equals(key.documentType())));return false;}).toList();
        return keys.stream().map(key->Map.<String,Object>of("siteCode",key.siteCode(),"documentType",key.documentType(),"siteLabel",siteLabel(key.siteCode()),"documentTypeLabel",documentLabel(key.documentType()))).toList();
    }
    private String siteLabel(String code){return switch(code){case "SEOUL_YOUTH_SAFE"->"서울특별시 청년안심주택";case "LH"->"LH";default->code;};}
    private String documentLabel(String code){return switch(code){case "PRIVATE_RENTAL_NOTICE"->"민간임대 모집공고";case "HAPPY_HOUSING_NOTICE"->"행복주택 모집공고";case "NATIONAL_RENTAL_NOTICE"->"국민임대 모집공고";default->code;};}
    List<Map<String,Object>> revisions(String announcementId){if(announcements.announcement(announcementId)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"공고를 찾을 수 없습니다.");return extractions.revisions(announcementId);}
    Map<String,Object> revisionDiff(String announcementId,String revisionId){
        if(announcements.announcement(announcementId)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"공고를 찾을 수 없습니다.");
        Map<String,Object> revision=extractions.revision(announcementId,revisionId);if(revision==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"이력을 찾을 수 없습니다.");
        Map<String,Object> result=new LinkedHashMap<>();result.put("revisionId",revision.get("id"));result.put("action",revision.get("action"));result.put("createdAt",revision.get("createdAt"));result.put("changes",DetailRevisionDiff.compare(map(revision.get("before")),map(revision.get("after"))));return result;
    }
    void restore(String announcementId,String revisionId){
        if(announcements.announcement(announcementId)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"공고를 찾을 수 없습니다.");
        Map<String,Object> revision=extractions.revision(announcementId,revisionId);if(revision==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"복원할 이력을 찾을 수 없습니다.");
        Map<String,Object> target=map(revision.get("after"));if(maps(target.get("sites")).isEmpty())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"공급 주택 정보가 없는 이력은 복원할 수 없습니다.");
        try{transactions.executeWithoutResult(status->{Map<String,Object> before=snapshot(announcementId);String now=Instant.now().toString();announcements.saveDetailHierarchy(announcementId,detailStatus(target),normalizedSites(target),now);extractions.replaceOperationalExtras(announcementId,target);Map<String,Object> after=snapshot(announcementId);String restoredRevisionId=extractions.recordRevision(announcementId,"RESTORED",null,null,revisionId,before,after,now);extractions.recordChangeEvents(announcementId,restoredRevisionId,before,after,now);});}
        catch(RuntimeException error){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"이력 복원에 실패했습니다.",error);}
    }

    private Map<String,Object> validateDraft(Map<String,Object> current){
        List<String> errors=new ArrayList<>();List<String>warnings=new ArrayList<>();Map<String,Object> draft=map(current.get("draft"));List<Map<String,Object>> sites=maps(draft.get("sites"));
        if(announcements.announcement(String.valueOf(current.get("announcementId")))==null)errors.add("기존 공고 연결이 유효하지 않습니다.");
        if(sites.isEmpty())errors.add("단지 정보가 없습니다.");
        for(Map<String,Object> site:sites){if(blank(site.get("name"))&&blank(site.get("address")))errors.add("단지명 또는 주소가 필요합니다.");List<Map<String,Object>> units=maps(site.get("units"));if(units.isEmpty())errors.add("공급 주택형이 필요합니다.");for(Map<String,Object> unit:units){for(Map<String,Object> option:maps(unit.get("rentalOptions"))){if(!Boolean.TRUE.equals(option.get("depositUnitConfirmed"))||!Boolean.TRUE.equals(option.get("monthlyRentUnitConfirmed")))errors.add("보증금·월세 원문 단위를 확인해 주세요.");}int supply=number(unit.get("supplyCount"));int rooms=maps(unit.get("rooms")).size();if(supply>0&&rooms>0&&supply!=rooms)warnings.add("공급호수와 개별 호실 수가 다릅니다.");}}
        for(Map<String,Object> event:maps(draft.get("events"))){String start=Objects.toString(event.get("startsAt"),"");String end=Objects.toString(event.get("endsAt"),"");if(!start.isBlank()&&!end.isBlank()&&start.compareTo(end)>0)errors.add("일정 시작일은 종료일보다 늦을 수 없습니다.");}
        List<Map<String,Object>> unreviewed=maps(current.get("evidence")).stream().filter(item->!Boolean.TRUE.equals(item.get("deleted"))).filter(item->!reviewed(item.get("reviewStatus"))).toList();
        if(!unreviewed.isEmpty()){
            String fields=unreviewed.stream().map(item->Objects.toString(item.get("fieldPath"),"원문 근거")).distinct().limit(3).reduce((left,right)->left+", "+right).orElse("원문 근거");
            errors.add("원문 근거 "+unreviewed.size()+"건을 확인해 주세요: "+fields+(unreviewed.stream().map(item->Objects.toString(item.get("fieldPath"),"")).distinct().count()>3?" 외":""));
        }
        if(extractions.committedHash(String.valueOf(current.get("announcementId")),String.valueOf(current.get("contentHash")),String.valueOf(current.get("id"))))errors.add("동일한 PDF가 이미 확정 저장되었습니다.");
        Object draftWarnings=draft.get("warnings");if(draftWarnings instanceof List<?> list)list.forEach(item->warnings.add(String.valueOf(item)));
        return Map.of("errors",errors.stream().distinct().toList(),"warnings",warnings.stream().distinct().toList(),"valid",errors.isEmpty());
    }
    private Map<String,Object> workItem(Map<String,Object> row){
        Map<String,Object> item=new LinkedHashMap<>(row);String extractionStatus=Objects.toString(row.get("extractionStatus"),""),detailStatus=Objects.toString(row.get("detailStatus"),"");int unreviewed=number(row.get("unreviewedCount"));boolean hasDetail=Boolean.TRUE.equals(row.get("hasDetail"))&&("PARSED".equals(detailStatus)||"PARTIAL".equals(detailStatus));
        String workStatus,action,description;
        if("COMMITTED".equals(extractionStatus)){workStatus="COMPLETED";action="HISTORY";description="확정된 PDF 상세정보가 사용자 화면에 반영되어 있습니다.";}
        else if("FAILED".equals(extractionStatus)){workStatus="ANALYSIS_FAILED";action="RETRY";description="PDF 분석에 실패했습니다. 원문과 문서 유형을 확인한 뒤 다시 분석하세요.";}
        else if(!extractionStatus.isBlank()&&"READY".equals(extractionStatus)&&unreviewed==0){workStatus="READY_TO_PUBLISH";action="PUBLISH";description="필수 확인이 끝났습니다. 운영 화면에 반영할 수 있습니다.";}
        else if(!extractionStatus.isBlank()){workStatus="REVIEW_REQUIRED";action="REVIEW";description="원문 근거 "+unreviewed+"건을 확인해야 합니다.";}
        else if(hasDetail){workStatus="DETAIL_AVAILABLE";action="DETAIL";description="HTML 상세 수집 결과가 있어 PDF 등록이 필요하지 않습니다.";}
        else if(supportedFor(row)){workStatus="PDF_REQUIRED";action="UPLOAD";description="상세정보를 채우려면 공고문 PDF를 등록하세요.";}
        else {workStatus="PDF_UNSUPPORTED";action="WAIT";description="현재 이 공고 유형의 PDF 자동 분석은 지원하지 않습니다.";}
        item.put("workStatus",workStatus);item.put("nextAction",action);item.put("description",description);return item;
    }
    private boolean supportedFor(Map<String,Object> item){
        String source=Objects.toString(item.get("sourceId"),""),housing=Objects.toString(item.get("housingType"),""),title=Objects.toString(item.get("title"),"");
        if("seoul-youth-safe".equals(source))return title.contains("민간임대");
        if("lh-rental-list".equals(source))return housing.contains("행복주택")||housing.contains("국민임대");
        return false;
    }
    private List<Map<String,Object>> normalizedSites(Map<String,Object> draft){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> raw:maps(draft.get("sites"))){Map<String,Object> site=new LinkedHashMap<>(raw);site.put("location",location(raw.get("address"),raw.get("location")));List<Map<String,Object>> units=new ArrayList<>();for(Map<String,Object> rawUnit:maps(raw.get("units"))){Map<String,Object> unit=new LinkedHashMap<>(rawUnit);List<Map<String,Object>> options=new ArrayList<>();for(Map<String,Object> rawOption:maps(rawUnit.get("rentalOptions"))){Map<String,Object> option=new LinkedHashMap<>();for(String key:List.of("optionKey","conditionText","depositWon","monthlyRentWon","managementFeeRawText","managementFeeWon","conversionConditionText"))option.put(key,rawOption.get(key));options.add(option);}unit.put("rentalOptions",options);List<Map<String,Object>> rooms=maps(rawUnit.get("rooms"));if(!rooms.isEmpty()){unit.put("floorText",rooms.getFirst().get("floorText"));unit.put("roomText",rooms.getFirst().get("roomText"));}units.add(unit);}site.put("units",units);result.add(site);}return result;}
    private Map<String,Object> location(Object rawAddress,Object savedLocation){Map<String,Object> saved=map(savedLocation);String address=Objects.toString(rawAddress,"").trim();if(address.isBlank())address=Objects.toString(saved.get("address"),"").trim();Map<String,Object> location=new LinkedHashMap<>();location.put("address",address);location.put("accuracy",address.isBlank()?Objects.toString(saved.getOrDefault("accuracy","UNKNOWN")):"EXACT");for(String key:List.of("latitude","longitude"))if(saved.get(key)!=null)location.put(key,saved.get(key));Matcher province=Pattern.compile("(서울특별시|서울시|경기도|인천광역시|부산광역시|대구광역시|광주광역시|대전광역시|울산광역시|세종특별자치시|강원특별자치도|충청북도|충청남도|전북특별자치도|전라남도|경상북도|경상남도|제주특별자치도)").matcher(address);if(province.find())location.put("region1",province.group(1));Matcher district=Pattern.compile("([가-힣]+(?:구|시|군))").matcher(address);while(district.find()){String candidate=district.group(1);if(!candidate.equals(location.get("region1"))){location.put("region2",candidate);break;}}Matcher neighborhood=Pattern.compile("([가-힣0-9]+(?:동|읍|면|리))").matcher(address);if(neighborhood.find())location.put("region3",neighborhood.group(1));for(String key:List.of("region1","region2","region3"))if(location.get(key)==null&&saved.get(key)!=null)location.put(key,saved.get(key));return location;}
    private Map<String,Object> snapshot(String announcementId){return new LinkedHashMap<>(Optional.ofNullable(announcements.detail(announcementId)).orElse(Map.of()));}
    private String detailStatus(Map<String,Object> detail){return "VERIFIED".equals(detail.get("verificationStatus"))?"PARSED":"PARTIAL".equals(detail.get("verificationStatus"))?"PARTIAL":"NEEDS_REVIEW";}
    private void addHtmlConflictWarnings(Map<String,Object> draft,Map<String,Object> announcement){List<String> conflicts=htmlConflictWarnings(draft,announcement);if(conflicts.isEmpty())return;List<String>warnings=new ArrayList<>();Object raw=draft.get("warnings");if(raw instanceof List<?> list)list.forEach(item->warnings.add(String.valueOf(item)));warnings.addAll(conflicts);draft.put("warnings",warnings);}
    static List<String> htmlConflictWarnings(Map<String,Object> draft,Map<String,Object> announcement){
        List<String>warnings=new ArrayList<>(),siteNames=new ArrayList<>(),providerNames=new ArrayList<>();
        for(Map<String,Object> site:mapsStatic(draft.get("sites"))){
            if(hasText(site.get("name")))siteNames.add(String.valueOf(site.get("name")));
            for(Map<String,Object> organization:mapsStatic(site.get("organizations"))){
                String role=Objects.toString(organization.get("role"),"");
                if(!"CONTRACTOR".equals(role)&&hasText(organization.get("organizationName")))providerNames.add(String.valueOf(organization.get("organizationName")));
            }
        }
        String title=Objects.toString(announcement.get("title"),"");
        if(!siteNames.isEmpty()&&hasText(title)&&siteNames.stream().noneMatch(name->sameName(name,title)))warnings.add("선택한 HTML 공고명과 PDF 단지명이 다릅니다. HTML 기본값은 변경하지 않습니다.");
        String provider=Objects.toString(announcement.get("provider"),"");
        if(!providerNames.isEmpty()&&hasText(provider)&&providerNames.stream().noneMatch(name->sameName(name,provider)))warnings.add("선택한 HTML 공급기관과 PDF 시행사·임대사업자 정보가 다릅니다. HTML 기본값은 변경하지 않습니다.");
        return warnings;
    }
    private static boolean sameName(String candidate,String reference){String left=normalizedName(candidate),right=normalizedName(reference);return !left.isEmpty()&&!right.isEmpty()&&(left.equals(right)||left.length()>=3&&right.contains(left)||right.length()>=3&&left.contains(right));}
    private static String normalizedName(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("(?:주식회사|유한회사|사단법인|재단법인|\\(주\\)|㈜)","").replaceAll("[^0-9a-z가-힣]","");}
    private static boolean hasText(Object value){return value!=null&&!String.valueOf(value).isBlank();}
    private static boolean reviewed(Object status){return "CONFIRMED".equals(status)||"MANUALLY_REVIEWED".equals(status);}
    private static List<String> changedPaths(Object before,Object after,String path){
        if(before instanceof Map<?,?> left&&after instanceof Map<?,?> right){Set<String> keys=new LinkedHashSet<>();left.keySet().forEach(key->keys.add(String.valueOf(key)));right.keySet().forEach(key->keys.add(String.valueOf(key)));List<String> result=new ArrayList<>();for(String key:keys)result.addAll(changedPaths(left.get(key),right.get(key),path.isEmpty()?key:path+"."+key));return result;}
        if(before instanceof List<?> left&&after instanceof List<?> right){List<String> result=new ArrayList<>();for(int index=0;index<Math.max(left.size(),right.size());index++)result.addAll(changedPaths(index<left.size()?left.get(index):null,index<right.size()?right.get(index):null,path+"["+index+"]"));return result;}
        return Objects.equals(before,after)?List.of():List.of(path);
    }
    private static List<Map<String,Object>> mapsStatic(Object value){if(!(value instanceof List<?> list))return List.of();List<Map<String,Object>> result=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> raw){Map<String,Object> mapped=new LinkedHashMap<>();raw.forEach((key,entry)->mapped.put(String.valueOf(key),entry));result.add(mapped);}return result;}
    private String sha(byte[] value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception error){throw new IllegalStateException(error);}}
    private boolean blank(Object value){return value==null||String.valueOf(value).isBlank();}private int number(Object value){return value instanceof Number n?n.intValue():0;}
    private Map<String,Object> map(Object value){if(!(value instanceof Map<?,?> raw))return new LinkedHashMap<>();Map<String,Object> result=new LinkedHashMap<>();raw.forEach((key,item)->result.put(String.valueOf(key),item));return result;}
    private List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().filter(Map.class::isInstance).map(this::map).toList();}
}
