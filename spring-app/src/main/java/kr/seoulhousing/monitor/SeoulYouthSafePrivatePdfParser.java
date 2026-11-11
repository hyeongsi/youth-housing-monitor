package kr.seoulhousing.monitor;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class SeoulYouthSafePrivatePdfParser implements DocumentParser {
    static final String ID="SEOUL_YOUTH_SAFE_PRIVATE_V1";
    private static final Pattern NUMBER=Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern MONEY=Pattern.compile("\\d{1,3}(?:,\\d{3})+");
    private static final Pattern FULL_DATE=Pattern.compile("(20\\d{2})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})");
    private static final Pattern PARTIAL_DATE=Pattern.compile("(?<!\\d)(\\d{1,2})[.\\-/](\\d{1,2})(?:[.])?");
    private static final Pattern TIME=Pattern.compile("(?:오전|오후)?\\s*(\\d{1,2})(?::|시)\\s*(\\d{2})?");

    @Override public String parserId(){return ID;}
    @Override public List<DocumentParserRegistry.Key> supportedKeys(){return List.of(new DocumentParserRegistry.Key("SEOUL_YOUTH_SAFE","PRIVATE_RENTAL_NOTICE"));}

    @Override public ParseResult parse(PdfTextLayoutExtractor.DocumentLayout document){
        if(!document.hasTextLayer()) return unsupported("UNSUPPORTED_NO_TEXT","텍스트 레이어가 없어 자동 추출할 수 없습니다. OCR 또는 직접 입력이 필요합니다.");
        String text=document.text();
        List<String> required=List.of("주택개요","공급일정및청약","공급현황","신청자격및제출서류");
        String compact=compact(text);
        List<String> missing=required.stream().filter(section->!compact.contains(section)).toList();
        if(!missing.isEmpty()) return unsupported("UNSUPPORTED_TEMPLATE","필수 섹션을 확인하지 못했습니다: "+String.join(", ",missing));

        List<String> warnings=new ArrayList<>();
        List<Evidence> evidence=new ArrayList<>();
        Map<String,Object> site=parseHousing(document,evidence,warnings);
        List<Map<String,Object>> units=parseSupply(document,evidence,warnings);
        site.put("units",units);
        List<Map<String,Object>> events=parseEvents(document,evidence,warnings);
        List<Map<String,Object>> rules=parseEligibility(document,evidence,warnings);
        if(units.isEmpty()) warnings.add("공급현황 표의 데이터 행을 찾지 못했습니다.");

        Map<String,Object> draft=new LinkedHashMap<>();
        draft.put("parserId",ID);
        draft.put("analysisStatus",units.isEmpty()?"NEEDS_REVIEW":"DRAFT");
        draft.put("sites",List.of(site));
        draft.put("events",events);
        draft.put("eligibilityRules",rules);
        draft.put("warnings",warnings);
        return new ParseResult(draft,evidence,warnings,true);
    }

    private ParseResult unsupported(String status,String warning){
        Map<String,Object> draft=new LinkedHashMap<>();
        draft.put("parserId",ID);draft.put("analysisStatus",status);draft.put("sites",List.of());
        return new ParseResult(draft,List.of(),List.of(warning),false);
    }

    private Map<String,Object> parseHousing(PdfTextLayoutExtractor.DocumentLayout document,List<Evidence> evidence,List<String> warnings){
        Map<String,Object> site=new LinkedHashMap<>();
        Line address=findLine(document,Pattern.compile("(?:주택위치|주소)\\s*:\\s*(.+)"));
        if(address!=null){
            String value=group(address.text(),Pattern.compile("(?:주택위치|주소)\\s*:\\s*(.+)"),1);
            Matcher guide=Pattern.compile("\\s*\\(([^()]*(?:역|출구)[^()]*)\\)\\s*$").matcher(value);
            if(guide.find()){site.put("locationGuide",guide.group(1).trim());value=value.substring(0,guide.start()).trim();}
            site.put("address",value);evidence.add(evidence(document,"sites[0].address",address.text(),"TEXT","MATCHED"));
        }
        Line name=findLine(document,Pattern.compile("(?:단\\s*지\\s*명|주택명|사업명)\\s*:\\s*(.+)"));
        if(name!=null){site.put("name",group(name.text(),Pattern.compile("(?:단\\s*지\\s*명|주택명|사업명)\\s*:\\s*(.+)"),1));evidence.add(evidence(document,"sites[0].name",name.text(),"TEXT","MATCHED"));}
        Line supply=findLine(document,Pattern.compile("공급호수\\s*:\\s*(.+)"));
        if(supply!=null){
            List<Long> values=numbers(supply.text());
            if(!values.isEmpty())site.put("totalHouseholds",values.getFirst().intValue());
            if(values.size()>1)site.put("publicSupportHouseholds",values.get(1).intValue());
            if(values.size()>2)site.put("supplyCount",values.getLast().intValue());
            evidence.add(evidence(document,"sites[0].supplyCount",supply.text(),"TEXT","MATCHED"));
        }
        List<Map<String,Object>> organizations=new ArrayList<>();
        addOrganization(document,organizations,evidence,"DEVELOPER",Pattern.compile("(?:시행사|사업주체)(?:\\s*\\(임대사업자\\))?\\s*:\\s*(.+)"));
        Line developer=findLine(document,Pattern.compile("(?:시행사|사업주체)(?:\\s*\\(임대사업자\\))?\\s*:\\s*(.+)"));
        if(developer!=null)organizations.add(Map.of("role","LANDLORD","organizationName",group(developer.text(),Pattern.compile("(?:시행사|사업주체)(?:\\s*\\(임대사업자\\))?\\s*:\\s*(.+)"),1)));
        addOrganization(document,organizations,evidence,"CONTRACTOR",Pattern.compile("(?:시\\s*공\\s*사|건설사)\\s*:\\s*(.+)"));
        site.put("organizations",organizations);
        if(blank(site.get("name"))&&blank(site.get("address")))warnings.add("주택개요에서 단지명과 주소를 찾지 못했습니다.");
        site.put("sourceSiteKey",slug(Objects.toString(site.get("name"),Objects.toString(site.get("address"),"site"))));
        return site;
    }

    private void addOrganization(PdfTextLayoutExtractor.DocumentLayout document,List<Map<String,Object>> result,List<Evidence> evidence,String role,Pattern pattern){
        Line line=findLine(document,pattern);if(line==null)return;
        result.add(Map.of("role",role,"organizationName",group(line.text(),pattern,1)));
        evidence.add(evidence(document,"sites[0].organizations["+(result.size()-1)+"]",line.text(),"TEXT","MATCHED"));
    }

    private List<Map<String,Object>> parseSupply(PdfTextLayoutExtractor.DocumentLayout document,List<Evidence> evidence,List<String> warnings){
        PdfTextLayoutExtractor.PageLayout page=pageWith(document,"[공급현황]");
        if(page==null)return List.of();
        float start=yOf(page,"[공급현황]",0),end=yOf(page,"[신청자격 및 제출서류]",Float.MAX_VALUE);
        String section=page.chunks().stream().filter(c->c.y()>=start&&c.y()<end).map(PdfTextLayoutExtractor.TextChunk::text).reduce("",(a,b)->a+" "+b),sectionKey=compact(section);
        long headerCount=List.of(List.of("공급유형"),List.of("전용면적","주거전용","전용"),List.of("공급호수","호수"),List.of("보증금액","임대보증금","보증금"),List.of("임대료","월임대료","월세")).stream().filter(aliases->aliases.stream().map(this::compact).anyMatch(sectionKey::contains)).count();
        if(headerCount<4)return List.of();
        String rawUnit=page.chunks().stream().filter(c->c.y()>=start&&c.y()<end&&c.text().contains("단위")).sorted(Comparator.comparing(PdfTextLayoutExtractor.TextChunk::y)).map(PdfTextLayoutExtractor.TextChunk::text).map(value->value.contains("백만원")?"백만원":value.contains("만원")?"만원":value.contains("원")?"원":null).filter(Objects::nonNull).findFirst().orElse("원");
        List<PdfTextLayoutExtractor.TextChunk> anchors=page.chunks().stream().filter(c->c.y()>start&&c.y()<end&&c.x()>170&&c.x()<300&&MONEY.matcher(c.text().trim()).matches()).sorted(Comparator.comparing(PdfTextLayoutExtractor.TextChunk::y)).toList();
        List<Map<String,Object>> units=new ArrayList<>();
        for(PdfTextLayoutExtractor.TextChunk deposit:anchors){
            PdfTextLayoutExtractor.TextChunk rent=nearest(page,c->c.y()>start&&c.y()<end&&c.x()>deposit.x()+50&&MONEY.matcher(c.text().trim()).matches(),deposit.y(),4);
            PdfTextLayoutExtractor.TextChunk area=nearest(page,c->c.y()>start&&c.y()<end&&c.x()>85&&c.x()<155&&c.text().matches(".*\\d+(?:\\.\\d+)?\\s*(?:㎡|m2|m²).*"),deposit.y(),18);
            PdfTextLayoutExtractor.TextChunk type=nearest(page,c->c.y()>start&&c.y()<end&&c.x()>85&&c.x()<155&&c.text().trim().matches("\\([^)]+\\)"),deposit.y(),18);
            PdfTextLayoutExtractor.TextChunk count=nearest(page,c->c.y()>start&&c.y()<end&&c.x()>145&&c.x()<190&&c.text().trim().matches("\\d+"),deposit.y(),5);
            if(rent==null||area==null||type==null||count==null)continue;
            PdfTextLayoutExtractor.TextChunk room=nearest(page,c->c.y()>deposit.y()&&c.y()<end&&c.x()>80&&c.x()<180&&c.text().trim().matches(".+호"),deposit.y(),22);
            String category=page.chunks().stream().filter(c->c.y()>deposit.y()-12&&c.y()<deposit.y()+12&&c.x()<100&&c.text().matches(".*[가-힣].*")).sorted(Comparator.comparing(PdfTextLayoutExtractor.TextChunk::y)).map(c->c.text().trim()).reduce("",(a,b)->(a+" "+b).trim());
            String roomText=room==null?"":room.text().trim(),floorText=findFloor(document,roomText);
            long depositWon=money(deposit.text()),rentWon=money(rent.text());
            boolean unitConflict=!"원".equals(rawUnit)&&(depositWon>=100_000||rentWon>=100_000);
            Map<String,Object> option=new LinkedHashMap<>();
            option.put("optionKey","option-"+units.size());option.put("conditionText","공급현황 표 기준");
            option.put("depositRawText",deposit.text().trim());option.put("depositRawUnit",rawUnit);option.put("depositWon",depositWon);option.put("depositUnitConfirmed",!unitConflict);
            option.put("monthlyRentRawText",rent.text().trim());option.put("monthlyRentRawUnit",rawUnit);option.put("monthlyRentWon",rentWon);option.put("monthlyRentUnitConfirmed",!unitConflict);
            Map<String,Object> unit=new LinkedHashMap<>();
            unit.put("sourceUnitKey",slug(type.text()+"-"+roomText+"-"+units.size()));unit.put("categoryText",category);unit.put("unitType",type.text().replaceAll("[()]","").trim());
            unit.put("exclusiveAreaM2",decimal(area.text()));unit.put("supplyCount",Integer.parseInt(count.text().trim()));
            unit.put("rooms",room==null?List.of():List.of(Map.of("buildingText","","floorText",floorText,"roomText",roomText,"sourceText",(floorText+" "+roomText).trim())));
            unit.put("rentalOptions",List.of(option));units.add(unit);
            int index=units.size()-1;
            evidence.add(evidence(document,"sites[0].units["+index+"].exclusiveAreaM2",area.text(),"TABLE","MATCHED"));
            evidence.add(evidence(document,"sites[0].units["+index+"].rooms[0]",roomText,"TABLE","MATCHED"));
            evidence.add(evidence(document,"sites[0].units["+index+"].rentalOptions[0].depositWon",deposit.text(),"TABLE",unitConflict?"NEEDS_REVIEW":"MATCHED"));
            evidence.add(evidence(document,"sites[0].units["+index+"].rentalOptions[0].monthlyRentWon",rent.text(),"TABLE",unitConflict?"NEEDS_REVIEW":"MATCHED"));
            if(unitConflict)warnings.add("공급현황 표의 원문 단위("+rawUnit+")와 금액 자릿수가 충돌합니다. "+type.text()+" 보증금·월세 단위를 확인해 주세요.");
        }
        return units;
    }

    private List<Map<String,Object>> parseEvents(PdfTextLayoutExtractor.DocumentLayout document,List<Evidence> evidence,List<String> warnings){
        List<Map<String,Object>> events=new ArrayList<>();
        Line notice=findLine(document,Pattern.compile("모집\\s*공고일은\\s*(20\\d{2}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2})"));
        Integer year=null;
        if(notice!=null){String date=date(group(notice.text(),Pattern.compile("모집\\s*공고일은\\s*(20\\d{2}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2})"),1));year=LocalDate.parse(date).getYear();events.add(event("ANNOUNCEMENT",date,date,"CONFIRMED","입주자 모집공고"));evidence.add(evidence(document,"events[0]",notice.text(),"TEXT","MATCHED"));}
        addLabeledEvent(document,events,evidence,"APPLICATION",List.of("청약신청","청약 신청","접수기간","신청기간"),"청약 신청");
        addLabeledEvent(document,events,evidence,"DOCUMENT_REVIEW",List.of("서류심사 대상자 발표","서류 심사 대상 발표"),"서류 심사 대상 발표");
        addLabeledEvent(document,events,evidence,"WINNER_ANNOUNCEMENT",List.of("당첨자 발표","당첨자발표"),"당첨자 발표");
        addLabeledEvent(document,events,evidence,"CONTRACT",List.of("계약체결","계약 체결"),"계약 체결");
        addLabeledEvent(document,events,evidence,"MOVE_IN",List.of("입주지정기간","입주 지정 기간"),"입주 지정 기간");
        PdfTextLayoutExtractor.PageLayout schedule=pageWith(document,"[공급일정 및 청약]");
        if(schedule!=null&&year!=null){
            ensureColumnEvent(events,evidence,document,schedule,"APPLICATION",500,590,year,"청약 신청","CONFIRMED");
            ensureColumnEvent(events,evidence,document,schedule,"DOCUMENT_REVIEW",595,655,year,"서류 심사 대상 발표","EXPECTED");
            ensureColumnEvent(events,evidence,document,schedule,"WINNER_ANNOUNCEMENT",660,735,year,"당첨자 발표","CONFIRMED");
            ensureColumnEvent(events,evidence,document,schedule,"CONTRACT",738,820,year,"계약 체결","CONFIRMED");
        }
        if(events.stream().noneMatch(event->"APPLICATION".equals(event.get("eventType"))))warnings.add("청약 신청 일정을 찾지 못했습니다.");
        return events;
    }

    private void addLabeledEvent(PdfTextLayoutExtractor.DocumentLayout document,List<Map<String,Object>> events,List<Evidence> evidence,String type,List<String> labels,String description){
        if(events.stream().anyMatch(item->type.equals(item.get("eventType"))))return;
        for(Line line:lines(document)){
            int labelIndex=labels.stream().map(label->compact(line.text()).indexOf(compact(label))).filter(index->index>=0).min(Integer::compare).orElse(-1);if(labelIndex<0)continue;
            Matcher firstDate=FULL_DATE.matcher(compact(line.text()));if(firstDate.find()&&firstDate.start()<labelIndex)continue;
            List<String> dates=dates(line.text(),null);if(dates.isEmpty())continue;List<String> times=times(line.text());
            String start=withTime(dates.getFirst(),times.isEmpty()?null:times.getFirst()),end=withTime(dates.size()>1?dates.get(1):dates.getFirst(),times.size()>1?times.get(1):null);
            events.add(event(type,start,end,Pattern.compile("(?:^|\\s)예정(?:\\s|$)").matcher(line.text()).find()?"EXPECTED":"CONFIRMED",description));
            evidence.add(evidence(document,"events["+(events.size()-1)+"]",line.text(),"TEXT","MATCHED"));return;
        }
    }

    private void ensureColumnEvent(List<Map<String,Object>> events,List<Evidence> evidence,PdfTextLayoutExtractor.DocumentLayout document,PdfTextLayoutExtractor.PageLayout page,String type,float minX,float maxX,int year,String description,String status){
        if(events.stream().anyMatch(item->type.equals(item.get("eventType"))))return;
        List<PdfTextLayoutExtractor.TextChunk> chunks=page.chunks().stream().filter(c->c.x()>=minX&&c.x()<maxX&&c.y()>195&&c.y()<310).toList();
        List<String> dates=new ArrayList<>();
        for(PdfTextLayoutExtractor.TextChunk chunk:chunks){dates.addAll(dates(chunk.text(),year));}
        dates=dates.stream().distinct().toList();if(dates.isEmpty())return;
        List<String> timeValues=chunks.stream().map(PdfTextLayoutExtractor.TextChunk::text).flatMap(value->times(value).stream()).toList();
        String start=withTime(dates.getFirst(),timeValues.isEmpty()?null:timeValues.getFirst()),end=withTime(dates.size()>1?dates.get(1):dates.getFirst(),timeValues.size()>1?timeValues.getLast():dates.size()==1&&!timeValues.isEmpty()?timeValues.getFirst():null);
        events.add(event(type,start,end,status,description));evidence.add(new Evidence("events["+(events.size()-1)+"]",page.pageNumber(),box(chunks),chunks.stream().map(PdfTextLayoutExtractor.TextChunk::text).reduce("",(a,b)->(a+" "+b).trim()),"TABLE","NEEDS_REVIEW"));
    }

    private List<Map<String,Object>> parseEligibility(PdfTextLayoutExtractor.DocumentLayout document,List<Evidence> evidence,List<String> warnings){
        List<Map<String,Object>> rules=new ArrayList<>();
        Line age=findLine(document,Pattern.compile("(\\d+)세\\s*이상\\s*(\\d+)세\\s*이하"));
        if(age!=null){Matcher matcher=Pattern.compile("(\\d+)세\\s*이상\\s*(\\d+)세\\s*이하").matcher(age.text());matcher.find();rules.add(rule("AGE","신청자",null,null,"BETWEEN",Long.parseLong(matcher.group(1)),"세",matcher.group()));evidence.add(evidence(document,"eligibilityRules[0]",age.text(),"TEXT","MATCHED"));}
        Line housing=findLine(document,Pattern.compile(".*무주택요건.*"));
        if(housing!=null){rules.add(rule("HOUSING","신청자 및 입주 예정 세대원",null,null,"EQUALS",null,null,housing.text().trim()));evidence.add(evidence(document,"eligibilityRules["+(rules.size()-1)+"]",housing.text(),"TEXT","MATCHED"));}
        Line vehicle=findLine(document,Pattern.compile("자동차가액\\s*([\\d,]+)만원"));
        if(vehicle!=null){long amount=money(group(vehicle.text(),Pattern.compile("자동차가액\\s*([\\d,]+)만원"),1))*10_000;rules.add(rule("VEHICLE","입주 예정 세대",null,null,"LTE",amount,"원",vehicle.text().trim()));evidence.add(evidence(document,"eligibilityRules["+(rules.size()-1)+"]",vehicle.text(),"TEXT","MATCHED"));}
        PdfTextLayoutExtractor.PageLayout page=pageWith(document,"[신청자격 및 제출서류]");
        if(page!=null){parseAssets(page,document,rules,evidence);parseRegions(page,document,rules,evidence);parseIncome(page,document,rules,evidence);}
        if(rules.isEmpty())warnings.add("신청자격 구조를 찾지 못했습니다.");
        return rules;
    }

    private void parseAssets(PdfTextLayoutExtractor.PageLayout page,PdfTextLayoutExtractor.DocumentLayout document,List<Map<String,Object>> rules,List<Evidence> evidence){
        PdfTextLayoutExtractor.TextChunk unit=page.chunks().stream().filter(c->c.text().contains("단위")&&c.text().contains("백만원")).findFirst().orElse(null);if(unit==null)return;
        List<PdfTextLayoutExtractor.TextChunk> values=page.chunks().stream().filter(c->Math.abs(c.y()-unit.y())<5&&c.x()>450&&c.x()<750&&c.text().trim().matches("\\d+")).sorted(Comparator.comparing(PdfTextLayoutExtractor.TextChunk::x)).toList();
        String[] targets={"청년","예비신혼부부"};for(int i=0;i<Math.min(values.size(),targets.length);i++){PdfTextLayoutExtractor.TextChunk value=values.get(i);rules.add(rule("ASSET",targets[i],null,null,"LTE",Long.parseLong(value.text().trim())*1_000_000,"원","자산 기준 "+value.text().trim()+"백만원 이하"));evidence.add(evidence(document,"eligibilityRules["+(rules.size()-1)+"]",value.text(),"TABLE","MATCHED"));}
    }

    private void parseRegions(PdfTextLayoutExtractor.PageLayout page,PdfTextLayoutExtractor.DocumentLayout document,List<Map<String,Object>> rules,List<Evidence> evidence){
        for(PdfTextLayoutExtractor.TextChunk rank:page.chunks().stream().filter(c->c.x()>450&&c.text().trim().matches("\\d+순위")).sorted(Comparator.comparing(PdfTextLayoutExtractor.TextChunk::y)).toList()){
            PdfTextLayoutExtractor.TextChunk region=nearest(page,c->c.x()>500&&c.x()<660&&c.text().matches(".*[가-힣].*"),rank.y(),3);if(region==null)continue;
            int value=Integer.parseInt(rank.text().replaceAll("\\D",""));String description=region.text().replaceFirst("거주자.*$","").trim();
            rules.add(rule("REGION","신청자",value,null,"EQUALS",null,null,description));evidence.add(evidence(document,"eligibilityRules["+(rules.size()-1)+"]",region.text(),"TABLE","MATCHED"));
        }
    }

    private void parseIncome(PdfTextLayoutExtractor.PageLayout page,PdfTextLayoutExtractor.DocumentLayout document,List<Map<String,Object>> rules,List<Evidence> evidence){
        for(PdfTextLayoutExtractor.TextChunk rank:page.chunks().stream().filter(c->c.x()<100&&c.text().trim().matches("\\d+순위")).sorted(Comparator.comparing(PdfTextLayoutExtractor.TextChunk::y)).toList()){
            int rankNumber=Integer.parseInt(rank.text().replaceAll("\\D",""));List<PdfTextLayoutExtractor.TextChunk> amounts=page.chunks().stream().filter(c->c.x()>100&&c.x()<400&&Math.abs(c.y()-rank.y())<3&&MONEY.matcher(c.text().trim()).matches()).sorted(Comparator.comparing(PdfTextLayoutExtractor.TextChunk::x)).toList();
            for(int i=0;i<amounts.size();i++){PdfTextLayoutExtractor.TextChunk amount=amounts.get(i);rules.add(rule("INCOME","신청자 세대",rankNumber,i+1,"LTE",money(amount.text()),"원",rankNumber+"순위 "+(i+1)+"인 가구 월평균소득 기준"));evidence.add(evidence(document,"eligibilityRules["+(rules.size()-1)+"]",amount.text(),"TABLE","MATCHED"));}
        }
    }

    private String findFloor(PdfTextLayoutExtractor.DocumentLayout document,String room){
        if(room.isBlank())return "";
        for(PdfTextLayoutExtractor.PageLayout page:document.pages())for(PdfTextLayoutExtractor.TextChunk roomChunk:page.chunks())if(roomChunk.text().trim().equals(room)){
            PdfTextLayoutExtractor.TextChunk floor=nearest(page,c->Math.abs(c.x()-roomChunk.x())<45&&c.y()<roomChunk.y()&&c.text().trim().matches(".+층"),roomChunk.y(),20);if(floor!=null)return floor.text().trim();
        }return "";
    }

    private Map<String,Object> event(String type,String start,String end,String status,String description){return Map.of("eventType",type,"startsAt",start,"endsAt",end,"status",status,"description",description);}
    private Map<String,Object> rule(String category,String target,Integer rank,Integer household,String operator,Number threshold,String unit,String description){Map<String,Object> value=new LinkedHashMap<>();value.put("category",category);value.put("targetGroup",target);value.put("rank",rank);value.put("householdSize",household);value.put("operator",operator);value.put("thresholdValue",threshold);value.put("thresholdUnit",unit);value.put("description",description);return value;}

    private Evidence evidence(PdfTextLayoutExtractor.DocumentLayout document,String path,String raw,String method,String status){
        String needle=raw==null?"":raw.trim();for(PdfTextLayoutExtractor.PageLayout page:document.pages())for(PdfTextLayoutExtractor.TextChunk chunk:page.chunks())if(chunk.text().contains(needle)||needle.contains(chunk.text().trim())&&chunk.text().trim().length()>3)return new Evidence(path,page.pageNumber(),Map.of("x",chunk.x(),"y",chunk.y(),"width",chunk.width(),"height",chunk.height()),raw,method,status);
        int page=document.pages().stream().filter(value->value.text().contains(needle)).map(PdfTextLayoutExtractor.PageLayout::pageNumber).findFirst().orElse(1);return new Evidence(path,page,Map.of(),raw,method,"NEEDS_REVIEW".equals(status)?status:"NEEDS_REVIEW");
    }
    private Map<String,Object> box(List<PdfTextLayoutExtractor.TextChunk> chunks){if(chunks.isEmpty())return Map.of();float minX=chunks.stream().map(PdfTextLayoutExtractor.TextChunk::x).min(Float::compare).orElse(0f),minY=chunks.stream().map(PdfTextLayoutExtractor.TextChunk::y).min(Float::compare).orElse(0f),maxX=chunks.stream().map(c->c.x()+c.width()).max(Float::compare).orElse(minX),maxY=chunks.stream().map(c->c.y()+c.height()).max(Float::compare).orElse(minY);return Map.of("x",minX,"y",minY,"width",maxX-minX,"height",maxY-minY);}
    private List<Line> lines(PdfTextLayoutExtractor.DocumentLayout document){List<Line> result=new ArrayList<>();for(PdfTextLayoutExtractor.PageLayout page:document.pages()){for(PdfTextLayoutExtractor.TextChunk chunk:page.chunks())result.add(new Line(page.pageNumber(),chunk.text()));for(String line:page.text().split("\\R"))result.add(new Line(page.pageNumber(),line));}return result;}
    private Line findLine(PdfTextLayoutExtractor.DocumentLayout document,Pattern pattern){return lines(document).stream().filter(line->pattern.matcher(line.text()).find()).findFirst().orElse(null);}
    private String group(String value,Pattern pattern,int group){Matcher matcher=pattern.matcher(value);return matcher.find()?matcher.group(group).trim():"";}
    private String match(String value,Pattern pattern,int group){Matcher matcher=pattern.matcher(value);return matcher.find()?matcher.group(group).trim():null;}
    private PdfTextLayoutExtractor.PageLayout pageWith(PdfTextLayoutExtractor.DocumentLayout document,String value){String compact=compact(value);return document.pages().stream().filter(page->compact(page.text()).contains(compact)).findFirst().orElse(null);}
    private float yOf(PdfTextLayoutExtractor.PageLayout page,String value,float fallback){String compact=compact(value);return page.chunks().stream().filter(chunk->compact(chunk.text()).contains(compact)).map(PdfTextLayoutExtractor.TextChunk::y).findFirst().orElse(fallback);}
    private PdfTextLayoutExtractor.TextChunk nearest(PdfTextLayoutExtractor.PageLayout page,java.util.function.Predicate<PdfTextLayoutExtractor.TextChunk> filter,float y,float tolerance){return page.chunks().stream().filter(filter).filter(c->Math.abs(c.y()-y)<=tolerance).min(Comparator.comparingDouble(c->Math.abs(c.y()-y))).orElse(null);}
    private List<Long> numbers(String value){List<Long> result=new ArrayList<>();Matcher matcher=NUMBER.matcher(value);while(matcher.find())result.add(money(matcher.group()));return result;}
    private long money(String value){String digits=value.replaceAll("[^0-9]","");return digits.isBlank()?0:Long.parseLong(digits);}
    private double decimal(String value){Matcher matcher=Pattern.compile("\\d+(?:\\.\\d+)?").matcher(value);return matcher.find()?Double.parseDouble(matcher.group()):0;}
    private List<String> dates(String value,Integer defaultYear){List<String> result=new ArrayList<>();Matcher full=FULL_DATE.matcher(value);while(full.find()){String date=validDate(Integer.parseInt(full.group(1)),Integer.parseInt(full.group(2)),Integer.parseInt(full.group(3)));if(date!=null)result.add(date);}if(result.isEmpty()&&defaultYear!=null){Matcher partial=PARTIAL_DATE.matcher(value);while(partial.find()){String date=validDate(defaultYear,Integer.parseInt(partial.group(1)),Integer.parseInt(partial.group(2)));if(date!=null)result.add(date);}}return result;}
    private String validDate(int year,int month,int day){try{return LocalDate.of(year,month,day).format(DateTimeFormatter.ISO_LOCAL_DATE);}catch(Exception ignored){return null;}}
    private List<String> times(String value){List<String> result=new ArrayList<>();Matcher matcher=TIME.matcher(value);while(matcher.find()){String raw=matcher.group();if(!raw.contains(":")&&!raw.contains("시"))continue;int hour=Integer.parseInt(matcher.group(1)),minute=matcher.group(2)==null?0:Integer.parseInt(matcher.group(2));if(raw.contains("오후")&&hour<12)hour+=12;if(raw.contains("오전")&&hour==12)hour=0;if(hour<24&&minute<60)result.add(String.format("%02d:%02d",hour,minute));}return result;}
    private String withTime(String date,String time){return time==null||time.isBlank()?date:date+"T"+time;}
    private String date(String value){List<String> values=dates(value,null);return values.isEmpty()?value:values.getFirst();}
    private String compact(String value){return value==null?"":value.replaceAll("[\\s\\[\\]]+","");}
    private String slug(String value){String normalized=value.toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]+","-").replaceAll("(^-|-$)","");return normalized.isBlank()?UUID.randomUUID().toString():normalized;}
    private boolean blank(Object value){return value==null||String.valueOf(value).isBlank();}
    private record Line(int page,String text){}
}
