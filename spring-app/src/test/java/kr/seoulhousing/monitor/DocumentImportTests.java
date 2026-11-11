package kr.seoulhousing.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentImportTests {
    private static final Path REFERENCE_PDF=Path.of("C:/Users/parks/Downloads/민간_261105_개봉역_세이지움 개봉_청년안심주택 추가모집공고문.pdf");
    @TempDir Path root;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void extractsReferencePdfCandidatesAndEvidence() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REFERENCE_PDF),"로컬 기준 PDF가 있을 때 실행");
        PdfTextLayoutExtractor.DocumentLayout layout=new PdfTextLayoutExtractor().extract(Files.readAllBytes(REFERENCE_PDF));
        DocumentParser.ParseResult result=new SeoulYouthSafePrivatePdfParser().parse(layout);
        assertEquals(4,layout.pageCount());
        assertTrue(result.supported());
        Map<String,Object> site=maps(result.draft().get("sites")).getFirst();
        assertEquals("세이지움 개봉",site.get("name"));
        Map<String,Object> unit=maps(site.get("units")).getFirst();
        assertEquals(36.65,((Number)unit.get("exclusiveAreaM2")).doubleValue());
        Map<String,Object> option=maps(unit.get("rentalOptions")).getFirst();
        assertEquals(151_000_000L,((Number)option.get("depositWon")).longValue());
        assertEquals(354_600L,((Number)option.get("monthlyRentWon")).longValue());
        assertEquals(15,maps(result.draft().get("eligibilityRules")).stream().filter(item->"INCOME".equals(item.get("category"))).count());
        assertTrue(result.evidence().stream().allMatch(item->item.pageNumber()>=1&&!item.rawText().isBlank()));
    }

    @Test void lhFixturePdfsProduceReviewableDraftsWithoutGuessingAmounts() throws Exception {
        Path fixtures=Path.of("../data/pdf-fixtures/lh");
        Assumptions.assumeTrue(Files.isDirectory(fixtures),"LH PDF fixture가 있을 때 실행");
        LhPublicRentalPdfParser parser=new LhPublicRentalPdfParser();
        List<Path> samples=List.of(fixtures.resolve("2026-pyeongtaek-national-rental.pdf"),fixtures.resolve("2026-uiryeong-happy-housing.pdf"));
        Assumptions.assumeTrue(samples.stream().allMatch(Files::isRegularFile),"LH 행복·국민임대 기준 PDF가 있을 때 실행");
        for(Path sample:samples){
            DocumentParser.ParseResult result=parser.parse(new PdfTextLayoutExtractor().extract(Files.readAllBytes(sample)));
            assertTrue(result.supported(),sample.toString());
            assertTrue(maps(result.draft().get("sites")).size()<=1);
            assertTrue(maps(result.draft().get("sites")).stream().flatMap(site->maps(site.get("units")).stream()).flatMap(unit->maps(unit.get("rentalOptions")).stream()).noneMatch(option->option.get("depositWon")!=null||option.get("monthlyRentWon")!=null));
            assertTrue(result.evidence().stream().allMatch(item->"NEEDS_REVIEW".equals(item.reviewStatus())));
        }
    }

    @Test void savesDraftValidatesCommitsAndKeepsOperationalDataAtomic() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REFERENCE_PDF),"로컬 기준 PDF가 있을 때 실행");
        Context context=context();
        Map<String,Object> created=context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",pdf("notice.pdf",Files.readAllBytes(REFERENCE_PDF)));
        String id=String.valueOf(created.get("id"));
        assertEquals("DRAFT",created.get("status"));
        assertFalse(String.valueOf(created.get("localPath")).contains("notice.pdf"));
        assertFalse((Boolean)context.service().validate(id).get("valid"),String.valueOf(created.get("draft")));

        Map<String,Object> draft=map(created.get("draft"));
        confirmAmounts(draft);
        context.service().update(id,draft);
        Map<String,Object> validation=context.service().validate(id);assertTrue((Boolean)validation.get("valid"),validation+" events="+draft.get("events"));
        assertEquals("COMMITTED",context.service().commit(id).get("status"));
        Map<String,Object> detail=context.announcements().detail("notice");
        assertEquals(1,maps(detail.get("sites")).size());
        Map<String,Object> savedSite=maps(detail.get("sites")).getFirst();
        assertEquals(3,maps(savedSite.get("organizations")).size());
        Map<String,Object> savedUnit=maps(savedSite.get("units")).getFirst();
        assertEquals(1,maps(savedUnit.get("rooms")).size());
        assertEquals(6,maps(detail.get("events")).size());
        assertEquals(23,maps(detail.get("eligibilityRules")).size());
        assertTrue(map(detail.get("application")).containsKey("start"));
        assertEquals(1,context.jdbc().queryForObject("SELECT COUNT(*) FROM supply_rooms",Integer.class));
        assertEquals(3,context.jdbc().queryForObject("SELECT COUNT(*) FROM site_organizations",Integer.class));
        assertEquals(6,context.jdbc().queryForObject("SELECT COUNT(*) FROM announcement_events",Integer.class));
        assertEquals(23,context.jdbc().queryForObject("SELECT COUNT(*) FROM eligibility_rules",Integer.class));
        List<Map<String,Object>> revisions=context.service().revisions("notice");
        assertEquals(1,revisions.size());
        assertEquals("PDF_COMMITTED",revisions.getFirst().get("action"));
        assertEquals(0,((Number)map(revisions.getFirst().get("before")).get("sites")).intValue());
        assertEquals(1,((Number)map(revisions.getFirst().get("after")).get("sites")).intValue());
        assertTrue(((List<?>)context.service().revisionDiff("notice",String.valueOf(revisions.getFirst().get("id"))).get("changes")).size()>0);
        assertThrows(ResponseStatusException.class,()->context.service().revisionDiff("other-notice",String.valueOf(revisions.getFirst().get("id"))));
        context.service().restore("notice",String.valueOf(revisions.getFirst().get("id")));
        assertEquals(2,context.jdbc().queryForObject("SELECT COUNT(*) FROM announcement_detail_revisions",Integer.class));
        assertTrue(context.service().revisions("notice").stream().anyMatch(item->"RESTORED".equals(item.get("action"))));
        assertTrue(context.jdbc().queryForObject("SELECT COUNT(*) FROM extraction_evidence WHERE review_status='NEEDS_REVIEW'",Integer.class)>0,"금액 확인만으로 다른 근거까지 자동 확정하면 안 된다");
        assertThrows(ResponseStatusException.class,()->context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",pdf("duplicate.pdf",Files.readAllBytes(REFERENCE_PDF))));
    }

    @Test void rejectsInvalidEncryptedAndTextlessPdf() throws Exception {
        Context context=context();
        assertThrows(ResponseStatusException.class,()->context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",pdf("fake.pdf","not a pdf".getBytes())));
        assertThrows(ResponseStatusException.class,()->context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",new MockMultipartFile("file","wrong.txt","text/plain",blankPdf())));
        assertThrows(ResponseStatusException.class,()->context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",pdf("large.pdf",new byte[20*1024*1024+1])));
        assertThrows(ResponseStatusException.class,()->context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",pdf("encrypted.pdf",encryptedPdf())));
        Map<String,Object> textless=context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",pdf("blank.pdf",blankPdf()));
        assertEquals("FAILED",textless.get("status"));
        assertEquals("UNSUPPORTED_NO_TEXT",map(textless.get("draft")).get("analysisStatus"));
    }

    @Test void doesNotInventReferenceValuesForAnotherTextPdf() throws Exception {
        PdfTextLayoutExtractor.DocumentLayout layout=new PdfTextLayoutExtractor().extract(otherTextPdf());
        DocumentParser.ParseResult result=new SeoulYouthSafePrivatePdfParser().parse(layout);
        assertFalse(result.supported());
        assertEquals("UNSUPPORTED_TEMPLATE",result.draft().get("analysisStatus"));
        assertTrue(maps(result.draft().get("sites")).isEmpty());
    }

    @Test void extractsDifferentValuesFromTheSameTemplateLayout() {
        DocumentParser.ParseResult result=new SeoulYouthSafePrivatePdfParser().parse(changedTemplateLayout());
        assertTrue(result.supported());
        Map<String,Object> site=maps(result.draft().get("sites")).getFirst();
        assertEquals("새봄 하우스",site.get("name"));
        assertEquals("서울특별시 마포구 테스트로 77",site.get("address"));
        assertEquals(2,((Number)site.get("supplyCount")).intValue());
        Map<String,Object> unit=maps(site.get("units")).getFirst();
        assertEquals("42C",unit.get("unitType"));
        assertEquals(42.75,((Number)unit.get("exclusiveAreaM2")).doubleValue());
        assertEquals("18층",maps(unit.get("rooms")).getFirst().get("floorText"));
        assertEquals("1802호",maps(unit.get("rooms")).getFirst().get("roomText"));
        Map<String,Object> option=maps(unit.get("rentalOptions")).getFirst();
        assertEquals(98_765_432L,((Number)option.get("depositWon")).longValue());
        assertEquals(567_890L,((Number)option.get("monthlyRentWon")).longValue());
        Map<String,Object> application=maps(result.draft().get("events")).stream().filter(item->"APPLICATION".equals(item.get("eventType"))).findFirst().orElseThrow();
        assertEquals("2027-02-05T09:00",application.get("startsAt"));
        assertEquals("2027-02-06T18:00",application.get("endsAt"));
    }

    @Test void htmlConflictWarningsUseExtractedSiteAndProviderInsteadOfReferenceConstants() {
        Map<String,Object> matching=Map.of("sites",List.of(Map.of(
                "name","새봄 하우스",
                "organizations",List.of(Map.of("role","DEVELOPER","organizationName","새봄개발(주)")))));
        Map<String,Object> announcement=Map.of("title","[추가모집] 새봄하우스 청년안심주택","provider","주식회사 새봄개발");
        assertTrue(DocumentImportService.htmlConflictWarnings(matching,announcement).isEmpty());

        Map<String,Object> wrongSite=Map.of("sites",List.of(Map.of(
                "name","다른 단지",
                "organizations",List.of(Map.of("role","LANDLORD","organizationName","새봄개발")))));
        List<String> siteWarnings=DocumentImportService.htmlConflictWarnings(wrongSite,announcement);
        assertEquals(1,siteWarnings.size());
        assertTrue(siteWarnings.getFirst().contains("공고명과 PDF 단지명"));

        Map<String,Object> wrongProvider=Map.of("sites",List.of(Map.of(
                "name","새봄 하우스",
                "organizations",List.of(Map.of("role","DEVELOPER","organizationName","다른임대사업자"),Map.of("role","CONTRACTOR","organizationName","새봄개발")))));
        List<String> providerWarnings=DocumentImportService.htmlConflictWarnings(wrongProvider,announcement);
        assertEquals(1,providerWarnings.size());
        assertTrue(providerWarnings.getFirst().contains("공급기관과 PDF 시행사·임대사업자"));

        assertTrue(DocumentImportService.htmlConflictWarnings(Map.of("sites",List.of(Map.of("units",List.of()))),announcement).isEmpty());
    }

    @Test void failedCommitRollsBackOperationalChangesButKeepsDraft() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REFERENCE_PDF),"로컬 기준 PDF가 있을 때 실행");
        Context context=context();
        Map<String,Object> created=context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",pdf("notice.pdf",Files.readAllBytes(REFERENCE_PDF)));
        String id=String.valueOf(created.get("id"));
        Map<String,Object> draft=map(created.get("draft"));
        confirmAmounts(draft);
        context.service().update(id,draft);
        context.jdbc().execute("CREATE TRIGGER reject_event BEFORE INSERT ON announcement_events BEGIN SELECT RAISE(ABORT, 'test rollback'); END");
        assertThrows(ResponseStatusException.class,()->context.service().commit(id));
        assertTrue(maps(context.announcements().detail("notice").get("sites")).isEmpty());
        assertNotEquals("COMMITTED",context.service().get(id).get("status"));
        assertEquals(1,context.jdbc().queryForObject("SELECT COUNT(*) FROM document_extractions",Integer.class));
        assertEquals(0,context.jdbc().queryForObject("SELECT COUNT(*) FROM announcement_detail_revisions",Integer.class));
    }

    @Test void patchAndReopenPreserveAllRepeatedRowsRuleFieldsAndEvidenceReview() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REFERENCE_PDF),"로컬 기준 PDF가 있을 때 실행");
        Context context=context();
        Map<String,Object> created=context.service().create("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE","notice",pdf("notice.pdf",Files.readAllBytes(REFERENCE_PDF)));
        String id=String.valueOf(created.get("id"));Map<String,Object> draft=map(created.get("draft"));
        List<Map<String,Object>> sites=new java.util.ArrayList<>(maps(draft.get("sites")));
        sites.add(Map.of("sourceSiteKey","second","name","둘째 단지","address","서울특별시 마포구","organizations",List.of(Map.of("role","OPERATOR","organizationName","운영사")),"units",List.of(Map.of("sourceUnitKey","B","unitType","B","exclusiveAreaM2",44.4,"supplyCount",2,"rooms",List.of(Map.of("buildingText","101동","floorText","3층","roomText","301호","sourceText","101동 3층 301호"),Map.of("buildingText","101동","floorText","4층","roomText","401호","sourceText","101동 4층 401호")),"rentalOptions",List.of(Map.of("optionKey","one","conditionText","기본","depositWon",10_000_000,"monthlyRentWon",500_000),Map.of("optionKey","two","conditionText","전환","depositWon",20_000_000,"monthlyRentWon",400_000))))));
        draft.put("sites",sites);
        List<Map<String,Object>> rules=new java.util.ArrayList<>(maps(draft.get("eligibilityRules")));rules.add(Map.of("category","INCOME","targetGroup","신혼부부","rank",2,"householdSize",4,"operator","LTE","thresholdValue",1_234_567,"thresholdUnit","원","description","테스트 기준"));draft.put("eligibilityRules",rules);
        Map<String,Object> firstEvidence=maps(created.get("evidence")).getFirst();
        context.service().update(id,Map.of("draft",draft,"evidenceReviews",List.of(Map.of("id",firstEvidence.get("id"),"fieldPath",firstEvidence.get("fieldPath"),"reviewStatus","MANUALLY_REVIEWED"),Map.of("fieldPath","sites[1]","pageNumber",1,"rawText","사용자 직접 입력","reviewStatus","MANUALLY_REVIEWED"))));
        Map<String,Object> reopened=context.service().get(id);Map<String,Object> reopenedDraft=map(reopened.get("draft"));
        assertEquals(2,maps(reopenedDraft.get("sites")).size());
        Map<String,Object> second=maps(reopenedDraft.get("sites")).get(1);Map<String,Object> secondUnit=maps(second.get("units")).getFirst();
        assertEquals(2,maps(secondUnit.get("rooms")).size());assertEquals(2,maps(secondUnit.get("rentalOptions")).size());
        Map<String,Object> savedRule=maps(reopenedDraft.get("eligibilityRules")).getLast();
        assertEquals(4,((Number)savedRule.get("householdSize")).intValue());assertEquals("LTE",savedRule.get("operator"));assertEquals(1_234_567,((Number)savedRule.get("thresholdValue")).intValue());assertEquals("원",savedRule.get("thresholdUnit"));
        Map<String,Object> reviewed=maps(reopened.get("evidence")).stream().filter(item->firstEvidence.get("id").equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("MANUAL",reviewed.get("method"));assertEquals("MANUALLY_REVIEWED",reviewed.get("reviewStatus"));
        assertTrue(maps(reopened.get("evidence")).stream().anyMatch(item->"sites[1]".equals(item.get("fieldPath"))&&"MANUAL".equals(item.get("method"))));
    }

    @Test void adminControllerExposesSearchUploadDraftAndOriginalPdf() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REFERENCE_PDF),"로컬 기준 PDF가 있을 때 실행");
        Context context=context();
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new DocumentImportController(context.service())).build();
        mvc.perform(get("/api/admin/announcements").param("query","세이지움"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("notice"));
        String body=mvc.perform(multipart("/api/admin/document-imports")
                        .file(pdf("reference.pdf",Files.readAllBytes(REFERENCE_PDF)))
                        .param("siteCode","SEOUL_YOUTH_SAFE").param("documentType","PRIVATE_RENTAL_NOTICE").param("announcementId","notice"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String id=String.valueOf(map(mapper.readValue(body,Map.class)).get("id"));
        mvc.perform(get("/api/admin/document-imports/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("$.draft.sites[0].name").value("세이지움 개봉"));
        mvc.perform(get("/api/admin/document-imports/{id}/document",id)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    }

    private Context context() throws Exception {
        Files.createDirectories(root.resolve("data"));
        mapper.writeValue(root.resolve("data/all-announcements.json").toFile(),Map.of("items",List.of(announcement())));
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("data/test.db")+"?foreign_keys=on&busy_timeout=5000&journal_mode=WAL");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        DataSourceTransactionManager transactions=new DataSourceTransactionManager(source);
        AnnouncementRepository announcements=new AnnouncementRepository(jdbc,transactions,mapper,root.toString());
        DocumentExtractionRepository extractions=new DocumentExtractionRepository(jdbc,mapper,announcements);
        DocumentImportService service=new DocumentImportService(announcements,extractions,new PdfTextLayoutExtractor(),new DocumentParserRegistry(List.of(new SeoulYouthSafePrivatePdfParser(),new LhPublicRentalPdfParser())),transactions,root.toString());
        return new Context(jdbc,announcements,service);
    }

    private Map<String,Object> announcement(){return Map.ofEntries(Map.entry("id","notice"),Map.entry("sourceId","fixture"),Map.entry("sourceName","Fixture"),Map.entry("provider","서울특별시"),Map.entry("title","세이지움 개봉 청년안심주택 추가모집"),Map.entry("housingType","청년안심주택"),Map.entry("announcedAt","2026-11-05"),Map.entry("applicationEnd","2026-11-10"),Map.entry("status","접수중"),Map.entry("officialUrl","https://example.test/notice"),Map.entry("retrievedAt","2026-11-08T10:00:00"));}
    private MockMultipartFile pdf(String name,byte[] bytes){return new MockMultipartFile("file",name,"application/pdf",bytes);}
    @SuppressWarnings("unchecked")
    private void confirmAmounts(Map<String,Object> draft){Map<String,Object> site=(Map<String,Object>)((List<?>)draft.get("sites")).getFirst();Map<String,Object> unit=(Map<String,Object>)((List<?>)site.get("units")).getFirst();Map<String,Object> option=(Map<String,Object>)((List<?>)unit.get("rentalOptions")).getFirst();option.put("depositUnitConfirmed",true);option.put("monthlyRentUnitConfirmed",true);}
    private byte[] blankPdf() throws Exception {try(PDDocument pdf=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){pdf.addPage(new PDPage());pdf.save(out);return out.toByteArray();}}
    private byte[] encryptedPdf() throws Exception {try(PDDocument pdf=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){pdf.addPage(new PDPage());AccessPermission access=new AccessPermission();pdf.protect(new StandardProtectionPolicy("owner","user",access));pdf.save(out);return out.toByteArray();}}
    private byte[] otherTextPdf() throws Exception {try(PDDocument pdf=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){PDPage page=new PDPage();pdf.addPage(page);try(PDPageContentStream content=new PDPageContentStream(pdf,page)){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(50,700);content.showText("Another supported-site document with enough searchable text.");content.endText();}pdf.save(out);return out.toByteArray();}}
    private PdfTextLayoutExtractor.DocumentLayout changedTemplateLayout(){
        List<PdfTextLayoutExtractor.TextChunk> first=List.of(
                chunk("[주택개요]",30,40),chunk("- 주택위치 : 서울특별시 마포구 테스트로 77 (2호선 테스트역 3번 출구)",30,60),chunk("- 공급호수 : 총 720세대 중 공공지원민간임대 410세대 중 금회 추가모집 특별공급 2세대",30,80),chunk("- 단 지 명 : 새봄 하우스",30,100),chunk("- 시행사(임대사업자) : 새봄개발(주)",30,120),chunk("- 시 공 사 : 우리건설",30,140),chunk("18층",510,150),chunk("1802호",510,160),chunk("[공급일정 및 청약]",450,200),chunk("본 공고문의 모집 공고일은 2027.02.01입니다.",450,220),chunk("청약신청 : 2027.02.05 09:00 ~ 2027.02.06 18:00",450,240),chunk("당첨자 발표 : 2027.02.10 ~ 2027.02.11",450,260),chunk("계약체결 : 2027.02.15 10:00 ~ 2027.02.16 17:00",450,280),chunk("입주지정기간 : 2027.03.01 10:00 ~ 2027.03.31 17:00",450,300));
        List<PdfTextLayoutExtractor.TextChunk> second=List.of(
                chunk("[공급현황]",30,40),chunk("공급 유형",55,70),chunk("주거 전용 (타입)",110,70),chunk("금회 공급 호수",155,70),chunk("보증금액",220,70),chunk("임대료",320,70),chunk("(단위 : 원)",340,80),chunk("청년 특별공급",50,110),chunk("42.75㎡",110,105),chunk("(42C)",115,115),chunk("2",162,115),chunk("98,765,432",220,115),chunk("567,890",325,115),chunk("1802호",115,125),chunk("[신청자격 및 제출서류]",30,200),chunk("신청자는 모집공고일 기준 20세 이상 38세 이하여야 합니다.",30,220),chunk("신청자 및 입주 예정 세대원 전부는 무주택요건을 충족해야 합니다.",30,240),chunk("자동차가액 5,000만원 이내",30,260));
        String firstText=first.stream().map(PdfTextLayoutExtractor.TextChunk::text).reduce("",(a,b)->a+"\n"+b),secondText=second.stream().map(PdfTextLayoutExtractor.TextChunk::text).reduce("",(a,b)->a+"\n"+b);
        return new PdfTextLayoutExtractor.DocumentLayout(2,true,List.of(new PdfTextLayoutExtractor.PageLayout(1,840,595,firstText,first),new PdfTextLayoutExtractor.PageLayout(2,840,595,secondText,second)));
    }
    private PdfTextLayoutExtractor.TextChunk chunk(String text,float x,float y){return new PdfTextLayoutExtractor.TextChunk(text,x,y,Math.max(20,text.length()*5),8);}
    private static Map<String,Object> map(Object value){Map<String,Object> result=new LinkedHashMap<>();if(value instanceof Map<?,?> raw)raw.forEach((key,item)->result.put(String.valueOf(key),item));return result;}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().map(DocumentImportTests::map).toList();}
    private record Context(JdbcTemplate jdbc,AnnouncementRepository announcements,DocumentImportService service){}
}
