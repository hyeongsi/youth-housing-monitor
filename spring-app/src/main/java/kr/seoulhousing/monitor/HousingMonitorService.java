package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@Service
public class HousingMonitorService {
    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>([\\s\\S]*?)</tr>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL = Pattern.compile("<t[dh]\\b[^>]*>([\\s\\S]*?)</t[dh]>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern DETAIL = Pattern.compile("getDetailView\\(\\s*['\\\"]?(\\d+)", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper;
    private final Path data;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public HousingMonitorService(ObjectMapper mapper, @Value("${housing.root}") String root) {
        this.mapper = mapper;
        this.data = Path.of(root).resolve("data").normalize();
    }

    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Seoul")
    public void scheduledRefresh() {
        refresh();
    }

    public synchronized Map<String, Object> refresh() {
        String checkedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        List<Map<String, Object>> sources = read("sources.json", new TypeReference<>() {}, List.of());
        List<Map<String, Object>> oldItems = read("all-announcements.json", new TypeReference<Map<String, Object>>() {}, Map.of()).containsKey("items")
                ? mapper.convertValue(read("all-announcements.json", new TypeReference<Map<String, Object>>() {}, Map.of()).get("items"), new TypeReference<List<Map<String, Object>>>() {}) : List.of();
        Set<String> known = new HashSet<>(); oldItems.forEach(item -> known.add(String.valueOf(item.get("id"))));
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> sourceState = new LinkedHashMap<>();
        List<Map<String, Object>> checked = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();

        for (Map<String, Object> source : sources) {
            String id = String.valueOf(source.get("id"));
            String name = String.valueOf(source.get("name"));
            String kind = String.valueOf(source.get("kind"));
            try {
                List<Map<String, Object>> found = switch (kind) {
                    case "lh-list" -> parseLh(get(String.valueOf(source.get("url"))), source, checkedAt);
                    case "sh-list" -> collectPaged(source, checkedAt, true);
                    case "seoul-public-list" -> collectPaged(source, checkedAt, false);
                    case "youth-safe-list" -> collectYouth(source, checkedAt);
                    default -> { get(String.valueOf(source.get("url"))); yield List.of(); }
                };
                records.addAll(found);
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("checkedAt", checkedAt); state.put("recordCount", found.size()); state.put("mode", kind.equals("snapshot") ? "snapshot" : "full");
                sourceState.put(id, state);
                checked.add(Map.of("source", name, "recordCount", found.size(), "mode", state.get("mode")));
            } catch (Exception error) {
                sourceState.put(id, Map.of("checkedAt", checkedAt, "error", error.getMessage()));
                failures.add(Map.of("source", name, "error", error.getMessage()));
            }
        }
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>(); records.forEach(item -> deduped.put(String.valueOf(item.get("id")), item));
        List<Map<String, Object>> items = new ArrayList<>(deduped.values());
        items.sort(Comparator.comparing(item -> String.valueOf(item.getOrDefault("announcedAt", "")), Comparator.reverseOrder()));
        List<Map<String, Object>> newItems = items.stream().filter(item -> !known.contains(String.valueOf(item.get("id")))).toList();
        write("all-announcements.json", Map.of("schemaVersion", 1, "checkedAt", checkedAt, "mode", "full", "itemCount", items.size(), "items", items));
        write("monitor-state.json", Map.of("schemaVersion", 3, "lastRunAt", checkedAt, "mode", "full", "runner", "spring", "sources", sourceState));
        write("auto-discovered.json", newItems);
        write("notifications.json", Map.of("checkedAt", checkedAt, "actionable", newItems, "sourceFailures", failures));
        return Map.of("checkedAt", checkedAt, "checked", checked, "failures", failures, "catalogCount", items.size());
    }

    private List<Map<String, Object>> collectPaged(Map<String, Object> source, String at, boolean sh) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>(); Set<String> seen = new HashSet<>(); int limit = ((Number) source.getOrDefault("fullPageLimit", 100)).intValue();
        for (int page = 1; page <= limit; page++) {
            String url = String.valueOf(source.get("url"));
            if (page > 1) url += (url.contains("?") ? "&" : "?") + source.getOrDefault("pageParam", "page") + "=" + page;
            List<Map<String, Object>> batch = sh ? parseSh(get(url), source, at) : parseSeoul(get(url), source, at);
            long added = batch.stream().filter(item -> seen.add(String.valueOf(item.get("id")))).peek(all::add).count();
            if (page > 1 && added == 0) break;
            Thread.sleep(200);
        }
        return all;
    }

    private List<Map<String, Object>> collectYouth(Map<String, Object> source, String at) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>(); int total = 1;
        for (int page = 1; page <= total; page++) {
            JsonNode json = post(String.valueOf(source.get("endpoint")), "bbsId=BMSR00015&pageIndex=" + page + "&searchAdresGu=&searchCondition=&searchKeyword=&optn2=&optn5=");
            total = json.path("pagingInfo").path("totPage").asInt(1);
            for (JsonNode row : json.path("resultList")) {
                String boardId = row.path("boardId").asText();
                String url = "https://soco.seoul.go.kr/youth/bbs/BMSR00015/view.do?optn1=" + enc(row.path("optn1").asText()) + "&boardId=" + boardId + "&menuNo=400008&pageIndex=" + page;
                all.add(record(source, boardId, row.path("nttSj").asText(), row.path("optn2").asText().equals("1") ? "공공임대" : "민간임대", "목록 기재 없음", row.path("optn1").asText(), row.path("optn4").asText(), row.path("optn5").asText(), row.path("optn3").asText(), url, at));
            }
            Thread.sleep(200);
        }
        return all;
    }

    private List<Map<String, Object>> parseLh(String html, Map<String, Object> source, String at) {
        List<Map<String, Object>> out = new ArrayList<>(); Matcher rows = ROW.matcher(html);
        while (rows.find()) { String row = rows.group(1); if (!row.contains("wrtancInfoBtn")) continue; List<String> c = cells(row);
            String pan = attr(row, "data-id1"), sys = attr(row, "data-id2"), upper = attr(row, "data-id3"), type = attr(row, "data-id4");
            Matcher anchor = Pattern.compile("wrtancInfoBtn[^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE).matcher(row); if (!anchor.find() || pan == null) continue;
            String url = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=" + enc(pan) + "&ccrCnntSysDsCd=" + enc(sys) + "&uppAisTpCd=" + enc(upper) + "&aisTpCd=" + enc(type) + "&mi=1026";
            out.add(record(source, pan, text(anchor.group(1)), cell(c,1), cell(c,3), cell(c,4), cell(c,5), cell(c,6), String.valueOf(source.get("name")), url, at)); }
        return out;
    }

    private List<Map<String, Object>> parseSh(String html, Map<String, Object> source, String at) {
        List<Map<String, Object>> out = new ArrayList<>(); Matcher rows = ROW.matcher(html);
        while (rows.find()) { String row=rows.group(1); Matcher m=DETAIL.matcher(row); if (!m.find()) continue; List<String> c=cells(row); String seq=m.group(1);
            out.add(record(source, seq, cell(c,1), "목록 기재 없음", "목록 기재 없음", cell(c,3), null, "목록 기재 없음", cell(c,2), String.valueOf(source.get("detailUrl"))+"?seq="+seq, at)); }
        return out;
    }

    private List<Map<String, Object>> parseSeoul(String html, Map<String, Object> source, String at) {
        List<Map<String, Object>> out = new ArrayList<>(); Matcher rows=ROW.matcher(html);
        while(rows.find()) { String row=rows.group(1); if(!row.contains("td1")) continue; List<String> c=cells(row); Matcher a=Pattern.compile("href=[\"']([^\"']+)[\"'][^>]*>[^<]*바로가기", Pattern.CASE_INSENSITIVE).matcher(row); if(!a.find()) continue;
            out.add(record(source,a.group(1),cell(c,2),cell(c,1),"목록 기재 없음",cell(c,3),cell(c,4),cell(c,5),cell(c,6),a.group(1),at)); }
        return out;
    }

    private Map<String,Object> record(Map<String,Object> source,String key,String title,String housing,String area,String announced,String end,String status,String provider,String url,String at){
        Map<String,Object> r=new LinkedHashMap<>(); r.put("id",sha(String.valueOf(source.get("id"))+":"+key)); r.put("sourceId",source.get("id")); r.put("sourceName",source.get("name")); r.put("provider",provider); r.put("title",title); r.put("housingType",housing); r.put("area",area); r.put("announcedAt",blank(announced)); r.put("applicationEnd",blank(end)); r.put("status",blank(status)); r.put("officialUrl",url); r.put("retrievedAt",at); return r; }
    private String get(String url) throws Exception { HttpRequest request=HttpRequest.newBuilder(URI.create(url)).header("User-Agent","SeoulHousingMonitor/1.0").GET().build(); HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString()); if(response.statusCode()/100!=2) throw new IOException("HTTP "+response.statusCode()); return response.body(); }
    private JsonNode post(String url,String body) throws Exception { HttpRequest request=HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/x-www-form-urlencoded").header("User-Agent","SeoulHousingMonitor/1.0").POST(HttpRequest.BodyPublishers.ofString(body)).build(); HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString()); if(response.statusCode()/100!=2) throw new IOException("HTTP "+response.statusCode()); return mapper.readTree(response.body()); }
    private List<String> cells(String row){ List<String> out=new ArrayList<>(); Matcher m=CELL.matcher(row); while(m.find())out.add(text(m.group(1))); return out; }
    private String attr(String html,String name){ Matcher m=Pattern.compile(name+"\\s*=\\s*[\"']([^\"']+)[\"']",Pattern.CASE_INSENSITIVE).matcher(html); return m.find()?m.group(1):null; }
    private String text(String value){ return TAG.matcher(value==null?"":value).replaceAll(" ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").replaceFirst("^-->\\s*","").trim(); }
    private String cell(List<String> cells,int index){ return index<cells.size()?cells.get(index):"목록 기재 없음"; }
    private String blank(String value){ return value==null||value.isBlank()?"목록 기재 없음":value; }
    private String enc(String value){ return URLEncoder.encode(value==null?"":value,StandardCharsets.UTF_8); }
    private String sha(String value){ try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte x:bytes)b.append(String.format("%02x",x)); return b.toString(); } catch(Exception e){throw new IllegalStateException(e);} }
    private <T> T read(String file,TypeReference<T> type,T fallback){ try { Path path=data.resolve(file); return Files.exists(path)?mapper.readValue(path.toFile(),type):fallback; } catch(IOException e){ return fallback; } }
    private void write(String file,Object value){ try { Files.createDirectories(data); Path target=data.resolve(file), temp=Files.createTempFile(data,file,".tmp"); mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),value); Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); } catch(IOException e){ throw new IllegalStateException(e); } }
}
