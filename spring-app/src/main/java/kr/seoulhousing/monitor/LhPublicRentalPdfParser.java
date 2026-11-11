package kr.seoulhousing.monitor;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.*;

/** LH 공고의 선택된 문서 유형만 처리한다. 불확실한 표 셀은 채우지 않고 근거를 검토 대상으로 남긴다. */
@Component
class LhPublicRentalPdfParser implements DocumentParser {
    private static final Pattern ADDRESS=Pattern.compile("(?:소재지|주소)\\s*[:：]\\s*([^\\n]+)");
    private static final Pattern AREA=Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*㎡");
    private static final Pattern COUNT=Pattern.compile("(?:공급(?:호수|세대)|모집(?:세대|인원))\\s*[:：]?\\s*(\\d[\\d,]*)");
    @Override public String parserId(){return "lh-public-rental-v1";}
    @Override public List<DocumentParserRegistry.Key> supportedKeys(){return List.of(new DocumentParserRegistry.Key("LH","HAPPY_HOUSING_NOTICE"),new DocumentParserRegistry.Key("LH","NATIONAL_RENTAL_NOTICE"));}
    @Override public ParseResult parse(PdfTextLayoutExtractor.DocumentLayout document,DocumentParserRegistry.Key key){
        String expected="HAPPY_HOUSING_NOTICE".equals(key.documentType())?"행복주택":"국민임대";
        String all=document.pages().stream().map(PdfTextLayoutExtractor.PageLayout::text).reduce("",(a,b)->a+"\n"+b);
        if(!all.contains(expected))return unsupported("UNSUPPORTED_TEMPLATE","선택한 문서 유형과 PDF 본문이 일치하지 않습니다.");
        return parse(document);
    }
    @Override public ParseResult parse(PdfTextLayoutExtractor.DocumentLayout document){
        if(!document.hasTextLayer())return unsupported("UNSUPPORTED_NO_TEXT","텍스트 레이어가 없어 자동 추출할 수 없습니다.");
        String all=document.pages().stream().map(PdfTextLayoutExtractor.PageLayout::text).reduce("",(a,b)->a+"\n"+b);
        if(!(all.contains("행복주택")||all.contains("국민임대")))return unsupported("UNSUPPORTED_TEMPLATE","선택한 LH 문서 유형과 PDF 본문이 일치하지 않습니다.");
        List<Evidence> evidence=new ArrayList<>();List<String> warnings=new ArrayList<>();Map<String,Object> site=new LinkedHashMap<>();
        Matcher address=ADDRESS.matcher(all);if(address.find()){site.put("address",address.group(1).trim());evidence.add(evidence(document,"sites[0].address",address.group(),"TEXT","NEEDS_REVIEW"));}
        site.put("name",firstLine(all,"공급정보",null));
        List<Map<String,Object>> units=new ArrayList<>();Matcher area=AREA.matcher(all);if(area.find()){
            Map<String,Object> unit=new LinkedHashMap<>();unit.put("sourceUnitKey","unit-1");unit.put("unitType",area.group(1)+"㎡");unit.put("exclusiveAreaM2",Double.parseDouble(area.group(1)));unit.put("supplyCount",0);unit.put("rooms",List.of());unit.put("rentalOptions",List.of());units.add(unit);evidence.add(evidence(document,"sites[0].units[0].exclusiveAreaM2",area.group(),"TEXT","NEEDS_REVIEW"));
        }else warnings.add("주택형·전용면적 표를 확실히 읽지 못했습니다.");
        Matcher count=COUNT.matcher(all);if(count.find()&&!units.isEmpty()){units.getFirst().put("supplyCount",Integer.parseInt(count.group(1).replace(",","")));evidence.add(evidence(document,"sites[0].units[0].supplyCount",count.group(),"TEXT","NEEDS_REVIEW"));}
        site.put("units",units);site.put("organizations",List.of());
        List<Map<String,Object>> events=new ArrayList<>();addEvent(events,evidence,document,all,"APPLICATION","접수기간");addEvent(events,evidence,document,all,"WINNER","당첨자발표");addEvent(events,evidence,document,all,"CONTRACT","계약기간");
        warnings.add("금액·임대조건·신청조건은 표 구조와 단위를 관리자 검토 후 입력하세요.");
        Map<String,Object> draft=new LinkedHashMap<>();draft.put("parserId",parserId());draft.put("analysisStatus","NEEDS_REVIEW");draft.put("sites",List.of(site));draft.put("events",events);draft.put("eligibilityRules",List.of());return new ParseResult(draft,evidence,warnings,true);
    }
    private void addEvent(List<Map<String,Object>> events,List<Evidence> evidence,PdfTextLayoutExtractor.DocumentLayout document,String all,String type,String label){int i=all.indexOf(label);if(i<0)return;String raw=all.substring(i,Math.min(all.length(),i+100)).split("\n")[0];Map<String,Object> event=new LinkedHashMap<>();event.put("eventType",type);event.put("description",raw);events.add(event);evidence.add(evidence(document,"events["+(events.size()-1)+"]",raw,"TEXT","NEEDS_REVIEW"));}
    private Evidence evidence(PdfTextLayoutExtractor.DocumentLayout document,String path,String raw,String method,String status){for(var page:document.pages())if(page.text().contains(raw))return new Evidence(path,page.pageNumber(),Map.of(),raw,method,status);return new Evidence(path,1,Map.of(),raw,method,"NEEDS_REVIEW");}
    private String firstLine(String all,String marker,String fallback){int i=all.indexOf(marker);if(i<0)return fallback;String[] lines=all.substring(i).split("\\R");return lines.length>1&&!lines[1].isBlank()?lines[1].trim():fallback;}
    private ParseResult unsupported(String status,String warning){return new ParseResult(Map.of("parserId",parserId(),"analysisStatus",status,"sites",List.of(),"events",List.of(),"eligibilityRules",List.of()),List.of(),List.of(warning),false);}
}
