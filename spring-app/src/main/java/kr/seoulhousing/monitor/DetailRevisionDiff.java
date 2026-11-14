package kr.seoulhousing.monitor;

import java.util.*;

final class DetailRevisionDiff {
    private static final Map<String,String> ORGANIZATION_LABELS=Map.of("DEVELOPER","시행사","LANDLORD","임대사업자","CONTRACTOR","시공사","OPERATOR","운영사");
    private static final Map<String,String> EVENT_LABELS=Map.of("ANNOUNCEMENT","공고","APPLICATION","청약 신청","DOCUMENT_REVIEW","서류 심사","WINNER_ANNOUNCEMENT","당첨자 발표","CONTRACT","계약","MOVE_IN","입주");
    private static final Map<String,String> RULE_LABELS=Map.of("AGE","연령","HOUSING","무주택","INCOME","소득","ASSET","자산","VEHICLE","자동차","REGION","거주 지역","HOUSEHOLD","세대 구성","OTHER","기타");
    private static final Map<String,String> OPERATOR_LABELS=Map.of("LTE","이하","GTE","이상","EQUALS","같음","BETWEEN","범위");

    private DetailRevisionDiff() {}

    static List<Map<String,Object>> compare(Map<String,Object> before,Map<String,Object> after){
        List<Map<String,Object>> changes=new ArrayList<>();
        compareSites(changes,before,after); compareEvents(changes,before,after); compareRules(changes,before,after);
        changes.sort(Comparator.comparing((Map<String,Object> change)->String.valueOf(change.get("section"))).thenComparing(change->String.valueOf(change.get("label"))));
        return changes;
    }

    private static void compareSites(List<Map<String,Object>> changes,Map<String,Object> before,Map<String,Object> after){
        Map<String,Map<String,Object>> left=indexed(maps(before.get("sites")),"sourceSiteKey"), right=indexed(maps(after.get("sites")),"sourceSiteKey");
        for(String key:keys(left,right)){
            Map<String,Object> oldSite=left.get(key),newSite=right.get(key);String siteLabel=first(newSite,oldSite,"name","단지");
            compare(changes,"공급 주택",siteLabel,"단지명",value(oldSite,"name"),value(newSite,"name"),DetailRevisionDiff::plain);
            compare(changes,"공급 주택",siteLabel,"주소",value(location(oldSite),"address"),value(location(newSite),"address"),DetailRevisionDiff::plain);
            compare(changes,"공급 주택",siteLabel,"공급호수",value(oldSite,"supplyCount"),value(newSite,"supplyCount"),DetailRevisionDiff::count);
            compareOrganizations(changes,siteLabel,oldSite,newSite); compareUnits(changes,siteLabel,oldSite,newSite);
        }
    }

    private static void compareOrganizations(List<Map<String,Object>> changes,String siteLabel,Map<String,Object> before,Map<String,Object> after){
        Map<String,Map<String,Object>> left=indexed(list(before,"organizations"),"role"),right=indexed(list(after,"organizations"),"role");
        for(String role:keys(left,right))compare(changes,"공급 주택",siteLabel+" · "+ORGANIZATION_LABELS.getOrDefault(role,"기관"),"기관명",value(left.get(role),"organizationName"),value(right.get(role),"organizationName"),DetailRevisionDiff::plain);
    }

    private static void compareUnits(List<Map<String,Object>> changes,String siteLabel,Map<String,Object> before,Map<String,Object> after){
        Map<String,Map<String,Object>> left=indexed(list(before,"units"),"sourceUnitKey"),right=indexed(list(after,"units"),"sourceUnitKey");
        for(String key:keys(left,right)){
            Map<String,Object> oldUnit=left.get(key),newUnit=right.get(key);String unitLabel=siteLabel+" · "+first(newUnit,oldUnit,"unitType","주택형");
            compare(changes,"공급 주택",unitLabel,"공급유형",value(oldUnit,"categoryText"),value(newUnit,"categoryText"),DetailRevisionDiff::plain);
            compare(changes,"공급 주택",unitLabel,"전용면적",value(oldUnit,"exclusiveAreaM2"),value(newUnit,"exclusiveAreaM2"),DetailRevisionDiff::area);
            compare(changes,"공급 주택",unitLabel,"공급호수",value(oldUnit,"supplyCount"),value(newUnit,"supplyCount"),DetailRevisionDiff::count);
            compareRooms(changes,unitLabel,oldUnit,newUnit); compareOptions(changes,unitLabel,oldUnit,newUnit);
        }
    }

    private static void compareRooms(List<Map<String,Object>> changes,String unitLabel,Map<String,Object> before,Map<String,Object> after){
        Set<String> left=roomLabels(list(before,"rooms")),right=roomLabels(list(after,"rooms"));
        for(String room:union(left,right))compare(changes,"공급 주택",unitLabel,"호실",left.contains(room)?room:null,right.contains(room)?room:null,DetailRevisionDiff::plain);
    }

    private static void compareOptions(List<Map<String,Object>> changes,String unitLabel,Map<String,Object> before,Map<String,Object> after){
        Map<String,Map<String,Object>> left=indexed(list(before,"rentalOptions"),"optionKey"),right=indexed(list(after,"rentalOptions"),"optionKey");
        for(String key:keys(left,right)){
            Map<String,Object> oldOption=left.get(key),newOption=right.get(key);String optionLabel=unitLabel+" · "+first(newOption,oldOption,"conditionText","임대조건");
            compare(changes,"임대 조건",unitLabel+" · "+key,"조건 설명",value(oldOption,"conditionText"),value(newOption,"conditionText"),DetailRevisionDiff::plain);
            compare(changes,"임대 조건",optionLabel,"보증금",value(oldOption,"depositWon"),value(newOption,"depositWon"),DetailRevisionDiff::won);
            compare(changes,"임대 조건",optionLabel,"월세",value(oldOption,"monthlyRentWon"),value(newOption,"monthlyRentWon"),DetailRevisionDiff::won);
            compare(changes,"임대 조건",optionLabel,"관리비",value(oldOption,"managementFeeWon"),value(newOption,"managementFeeWon"),DetailRevisionDiff::won);
            compare(changes,"임대 조건",optionLabel,"전환 조건",value(oldOption,"conversionConditionText"),value(newOption,"conversionConditionText"),DetailRevisionDiff::plain);
        }
    }

    private static void compareEvents(List<Map<String,Object>> changes,Map<String,Object> before,Map<String,Object> after){
        Map<String,Map<String,Object>> left=indexed(maps(before.get("events")),"eventType"),right=indexed(maps(after.get("events")),"eventType");
        for(String type:keys(left,right)){Map<String,Object> oldEvent=left.get(type),newEvent=right.get(type);String label=EVENT_LABELS.getOrDefault(type,"일정");compare(changes,"일정",label,"시작",value(oldEvent,"startsAt"),value(newEvent,"startsAt"),DetailRevisionDiff::plain);compare(changes,"일정",label,"종료",value(oldEvent,"endsAt"),value(newEvent,"endsAt"),DetailRevisionDiff::plain);compare(changes,"일정",label,"상태",value(oldEvent,"status"),value(newEvent,"status"),DetailRevisionDiff::plain);compare(changes,"일정",label,"설명",value(oldEvent,"description"),value(newEvent,"description"),DetailRevisionDiff::plain);}
    }

    private static void compareRules(List<Map<String,Object>> changes,Map<String,Object> before,Map<String,Object> after){
        Map<String,Map<String,Object>> left=indexedRules(maps(before.get("eligibilityRules"))),right=indexedRules(maps(after.get("eligibilityRules")));
        for(String key:keys(left,right)){Map<String,Object> oldRule=left.get(key),newRule=right.get(key);String label=RULE_LABELS.getOrDefault(first(newRule,oldRule,"category","OTHER"),"기타");compare(changes,"신청 조건",label,"대상",value(oldRule,"targetGroup"),value(newRule,"targetGroup"),DetailRevisionDiff::plain);compare(changes,"신청 조건",label,"순위",value(oldRule,"rank"),value(newRule,"rank"),DetailRevisionDiff::rank);compare(changes,"신청 조건",label,"가구원 수",value(oldRule,"householdSize"),value(newRule,"householdSize"),DetailRevisionDiff::household);compare(changes,"신청 조건",label,"기준값",value(oldRule,"thresholdValue"),value(newRule,"thresholdValue"),DetailRevisionDiff::plain);compare(changes,"신청 조건",label,"연산자",value(oldRule,"operator"),value(newRule,"operator"),value->OPERATOR_LABELS.getOrDefault(String.valueOf(value),plain(value)));compare(changes,"신청 조건",label,"단위",value(oldRule,"thresholdUnit"),value(newRule,"thresholdUnit"),DetailRevisionDiff::plain);compare(changes,"신청 조건",label,"설명",value(oldRule,"description"),value(newRule,"description"),DetailRevisionDiff::plain);}
    }

    private static void compare(List<Map<String,Object>> changes,String section,String parent,String field,Object before,Object after,java.util.function.Function<Object,String> formatter){
        if(equal(before,after))return;
        String oldValue=missing(before)?"없음":formatter.apply(before),newValue=missing(after)?"없음":formatter.apply(after),kind=missing(before)?"ADDED":missing(after)?"REMOVED":"CHANGED";
        Map<String,Object> change=new LinkedHashMap<>();
        change.put("section",section);change.put("label",parent+" · "+field);change.put("field",field);change.put("before",oldValue);change.put("after",newValue);change.put("beforeRaw",before);change.put("afterRaw",after);change.put("kind",kind);
        changes.add(change);
    }
    private static Map<String,Map<String,Object>> indexed(List<Map<String,Object>> items,String key){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(int index=0;index<items.size();index++){Map<String,Object> item=items.get(index);String value=plain(item.get(key));result.put(value.isBlank()?"#"+index:value,item);}return result;}
    private static Map<String,Map<String,Object>> indexedRules(List<Map<String,Object>> items){Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(int index=0;index<items.size();index++){Map<String,Object> item=items.get(index);result.put(plain(item.get("category"))+":"+plain(item.get("targetGroup"))+":"+plain(item.get("rank"))+":"+index,item);}return result;}
    private static List<String> keys(Map<String,?> left,Map<String,?> right){Set<String> keys=new LinkedHashSet<>(left.keySet());keys.addAll(right.keySet());return new ArrayList<>(keys);}
    private static Set<String> union(Set<String> left,Set<String> right){Set<String> values=new LinkedHashSet<>(left);values.addAll(right);return values;}
    private static Set<String> roomLabels(List<Map<String,Object>> rooms){Set<String> labels=new LinkedHashSet<>();for(Map<String,Object> room:rooms){String label=List.of(plain(room.get("buildingText")),plain(room.get("floorText")),plain(room.get("roomText"))).stream().filter(value->!value.isBlank()).reduce((left,right)->left+" "+right).orElse(plain(room.get("sourceText")));if(!label.isBlank())labels.add(label);}return labels;}
    private static Map<String,Object> location(Map<String,Object> site){Object value=value(site,"location");if(!(value instanceof Map<?,?> raw))return Map.of();Map<String,Object> result=new LinkedHashMap<>();raw.forEach((key,item)->result.put(String.valueOf(key),item));return result;}
    private static List<Map<String,Object>> list(Map<String,Object> map,String key){return maps(value(map,key));}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> list))return List.of();List<Map<String,Object>> result=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> raw){Map<String,Object> mapped=new LinkedHashMap<>();raw.forEach((key,entry)->mapped.put(String.valueOf(key),entry));result.add(mapped);}return result;}
    private static Object value(Map<String,Object> map,String key){return map==null?null:map.get(key);}
    private static String first(Map<String,Object> preferred,Map<String,Object> fallback,String key,String defaultValue){String value=plain(value(preferred,key));return value.isBlank()?Optional.ofNullable(plain(value(fallback,key))).filter(item->!item.isBlank()).orElse(defaultValue):value;}
    private static boolean equal(Object left,Object right){return plain(left).equals(plain(right));}
    private static boolean missing(Object value){return plain(value).isBlank();}
    private static String plain(Object value){return value==null?"":String.valueOf(value).trim();}
    private static String won(Object value){try{return String.format(Locale.KOREA,"%,d원",Long.parseLong(plain(value)));}catch(Exception ignored){return plain(value);}}
    private static String area(Object value){return plain(value)+"㎡";}
    private static String count(Object value){return plain(value)+"호";}
    private static String rank(Object value){return plain(value)+"순위";}
    private static String household(Object value){return plain(value)+"인";}
}
