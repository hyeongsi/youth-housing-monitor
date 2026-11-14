package kr.seoulhousing.monitor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailRevisionDiffTests {
    @Test void formatsRentalMoneyAndReportsAddedRemovedAndChangedValues() {
        Map<String,Object> before=Map.of(
                "sites",List.of(site(151_000_000L)),
                "events",List.of(),"eligibilityRules",List.of(Map.of("category","AGE","targetGroup","청년","rank",1,"description","기존 조건")));
        Map<String,Object> after=Map.of(
                "sites",List.of(site(155_000_000L)),
                "events",List.of(Map.of("eventType","APPLICATION","startsAt","2026-11-12T09:00","endsAt","2026-11-13T18:00","status","CONFIRMED","description","청약 신청")),"eligibilityRules",List.of());

        List<Map<String,Object>> changes=DetailRevisionDiff.compare(before,after);
        Map<String,Object> deposit=changes.stream().filter(change->String.valueOf(change.get("label")).endsWith("보증금")).findFirst().orElseThrow();
        assertEquals("임대 조건",deposit.get("section"));
        assertEquals("151,000,000원",deposit.get("before"));
        assertEquals("155,000,000원",deposit.get("after"));
        assertEquals("CHANGED",deposit.get("kind"));
        assertTrue(changes.stream().anyMatch(change->"일정".equals(change.get("section"))&&"ADDED".equals(change.get("kind"))));
        assertTrue(changes.stream().anyMatch(change->"신청 조건".equals(change.get("section"))&&"REMOVED".equals(change.get("kind"))));
    }

    @Test void returnsNoChangesForIdenticalSnapshots() {
        Map<String,Object> snapshot=Map.of("sites",List.of(),"events",List.of(),"eligibilityRules",List.of());
        assertTrue(DetailRevisionDiff.compare(snapshot,snapshot).isEmpty());
    }

    @Test void reportsManagementFeeAndConversionConditionChanges() {
        Map<String,Object> before=Map.of("sites",List.of(siteWithRentalExtras(30_000L,"보증금 전환 불가")),"events",List.of(),"eligibilityRules",List.of());
        Map<String,Object> after=Map.of("sites",List.of(siteWithRentalExtras(35_000L,"보증금 전환 가능")),"events",List.of(),"eligibilityRules",List.of());

        List<Map<String,Object>> changes=DetailRevisionDiff.compare(before,after);
        assertTrue(changes.stream().anyMatch(change->"관리비".equals(change.get("field"))&&"30,000원".equals(change.get("before"))&&"35,000원".equals(change.get("after"))));
        assertTrue(changes.stream().anyMatch(change->"전환 조건".equals(change.get("field"))&&"보증금 전환 불가".equals(change.get("before"))&&"보증금 전환 가능".equals(change.get("after"))));
    }

    private Map<String,Object> site(long deposit){return Map.of("sourceSiteKey","sage","name","세이지움 개봉","location",Map.of("address","서울특별시 구로구"),"units",List.of(Map.of("sourceUnitKey","36A","unitType","36A","exclusiveAreaM2",36.65,"rentalOptions",List.of(Map.of("optionKey","base","conditionText","기본","depositWon",deposit,"monthlyRentWon",354_600L)))));}
    private Map<String,Object> siteWithRentalExtras(long managementFee,String conversionCondition){return Map.of("sourceSiteKey","sage","name","세이지움 개봉","location",Map.of("address","서울특별시 구로구"),"units",List.of(Map.of("sourceUnitKey","36A","unitType","36A","exclusiveAreaM2",36.65,"rentalOptions",List.of(Map.of("optionKey","base","conditionText","기본","depositWon",151_000_000L,"monthlyRentWon",354_600L,"managementFeeWon",managementFee,"conversionConditionText",conversionCondition)))));}
}
