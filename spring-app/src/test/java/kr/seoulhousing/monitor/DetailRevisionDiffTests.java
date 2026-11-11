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

    private Map<String,Object> site(long deposit){return Map.of("sourceSiteKey","sage","name","세이지움 개봉","location",Map.of("address","서울특별시 구로구"),"units",List.of(Map.of("sourceUnitKey","36A","unitType","36A","exclusiveAreaM2",36.65,"rentalOptions",List.of(Map.of("optionKey","base","conditionText","기본","depositWon",deposit,"monthlyRentWon",354_600L)))));}
}
