package kr.seoulhousing.monitor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class LhDetailParser {
    static final String VERSION = "lh-html-v1";
    private static final Pattern COORDINATE = Pattern.compile("var\\s+%s_\\d+\\s*=\\s*[\"'](-?\\d+(?:\\.\\d+)?)[\"']");

    Result parse(String html) {
        Document document = Jsoup.parse(html);
        List<Map<String, Object>> sites = new ArrayList<>();
        boolean complete = true;
        for (Element block : document.select("div[id^=wrtancSbdTab]")) {
            Map<String, Object> site = parseSite(block);
            if (site == null) continue;
            sites.add(site);
            complete &= isComplete(site);
        }
        if (sites.isEmpty()) throw new IllegalArgumentException("LH 공급정보를 찾지 못했습니다.");
        return new Result(complete ? "PARSED" : "PARTIAL", sites);
    }

    private Map<String, Object> parseSite(Element block) {
        Element addressItem = block.selectFirst("li:matchesOwn(^\\s*소재지\\s*:)");
        Element unitTable = block.select("table").stream()
                .filter(table -> table.select("caption").text().contains("주택형 안내"))
                .findFirst().orElse(null);
        if (addressItem == null && unitTable == null) return null;

        String sourceKey = block.id().replace("wrtancSbdTab", "");
        String name = text(block.selectFirst("h4.tit2"));
        String address = valueAfterColon(text(addressItem));
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("sourceSiteKey", sourceKey.isBlank() ? "site-" + name : sourceKey);
        site.put("canonicalKey", normalize(address));
        site.put("name", name.isBlank() ? address : name);
        site.put("moveInText", listValue(block, "입주예정월"));
        site.put("location", location(address, block.html()));
        site.put("units", unitTable == null ? List.of() : units(unitTable));
        return site;
    }

    private List<Map<String, Object>> units(Element table) {
        List<String> headers = table.select("thead th").eachText().stream().map(this::normalize).toList();
        int type = column(headers, "주택형");
        int area = column(headers, "전용면적(㎡)", "전용면적");
        int supply = column(headers, "금회공급세대수");
        int deposit = column(headers, "임대보증금(원)", "임대보증금");
        int rent = column(headers, "월임대료(원)", "월임대료");
        List<Map<String, Object>> units = new ArrayList<>();
        for (Element row : table.select("tbody > tr")) {
            Elements cells = row.select("th,td");
            String unitType = cell(cells, type);
            Double exclusiveArea = decimal(cell(cells, area));
            if (unitType.isBlank() && exclusiveArea == null) continue;
            Map<String, Object> unit = new LinkedHashMap<>();
            unit.put("sourceUnitKey", unitType.isBlank() ? "unit-" + units.size() : unitType);
            unit.put("unitType", unitType);
            unit.put("exclusiveAreaM2", exclusiveArea);
            unit.put("supplyCount", integer(cell(cells, supply)));
            Long depositWon = money(cell(cells, deposit));
            Long monthlyRentWon = money(cell(cells, rent));
            if (depositWon != null || monthlyRentWon != null) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("optionKey", "default");
                option.put("conditionText", "기본 임대조건");
                option.put("depositWon", depositWon);
                option.put("monthlyRentWon", monthlyRentWon);
                unit.put("rentalOptions", List.of(option));
            } else unit.put("rentalOptions", List.of());
            units.add(unit);
        }
        return units;
    }

    private Map<String, Object> location(String address, String html) {
        String[] parts = address.split("\\s+");
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("region1", parts.length > 0 ? parts[0] : null);
        location.put("region2", parts.length > 1 ? parts[1] : null);
        location.put("region3", parts.length > 2 && parts[2].matches(".*(구|군|읍|면|동)$") ? parts[2] : null);
        location.put("address", address.isBlank() ? null : address);
        location.put("latitude", coordinate(html, "lat"));
        location.put("longitude", coordinate(html, "lng"));
        location.put("accuracy", address.isBlank() ? "UNKNOWN" : "EXACT");
        return location;
    }

    @SuppressWarnings("unchecked")
    private boolean isComplete(Map<String, Object> site) {
        Map<String, Object> location = (Map<String, Object>) site.get("location");
        List<Map<String, Object>> units = (List<Map<String, Object>>) site.get("units");
        if (location.get("address") == null || units.isEmpty()) return false;
        for (Map<String, Object> unit : units) {
            List<Map<String, Object>> options = (List<Map<String, Object>>) unit.get("rentalOptions");
            if (unit.get("exclusiveAreaM2") == null || unit.get("supplyCount") == null || options.isEmpty()) return false;
            Map<String, Object> option = options.getFirst();
            if (option.get("depositWon") == null || option.get("monthlyRentWon") == null) return false;
        }
        return true;
    }

    private String listValue(Element block, String label) {
        Element item = block.select("li").stream().filter(li -> text(li).startsWith(label)).findFirst().orElse(null);
        return valueAfterColon(text(item));
    }

    private String valueAfterColon(String value) {
        int colon = value.indexOf(':');
        return colon < 0 ? value.trim() : value.substring(colon + 1).trim();
    }

    private int column(List<String> headers, String... names) {
        for (String name : names) {
            String normalized = normalize(name);
            for (int i = 0; i < headers.size(); i++) if (headers.get(i).equals(normalized)) return i;
        }
        return -1;
    }

    private String cell(Elements cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index).text().trim() : "";
    }

    private Double decimal(String value) {
        try { return value.isBlank() || value.equals("-") ? null : Double.valueOf(value.replaceAll("[^0-9.]", "")); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Integer integer(String value) {
        Long number = money(value);
        return number == null || number > Integer.MAX_VALUE ? null : number.intValue();
    }

    private Long money(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        try { return digits.isBlank() ? null : Long.valueOf(digits); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Double coordinate(String html, String name) {
        Matcher matcher = Pattern.compile(COORDINATE.pattern().formatted(name)).matcher(html);
        return matcher.find() ? Double.valueOf(matcher.group(1)) : null;
    }

    private String text(Element element) { return element == null ? "" : element.text().trim(); }
    private String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }

    record Result(String status, List<Map<String, Object>> sites) {}
}
