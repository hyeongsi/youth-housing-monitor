package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class HousingDataController {
    private final ObjectMapper mapper;
    private final Path data;
    private final AnnouncementRepository announcements;
    private static final Set<String> PROFILE_FIELDS = Set.of("employmentStatus", "grossIncomeBand", "assetBand", "cashForDepositBand", "maxMonthlyRent", "maxDeposit", "homeOwnership", "maritalAndHousehold", "livesSeparatelyFromParents", "vehicleOwnership", "preferredAreas", "commuteOrSchoolCondition", "currentLeaseEnd", "earliestMoveDate", "notes");

    @Autowired
    public HousingDataController(ObjectMapper mapper, @Value("${housing.root}") String root, AnnouncementRepository announcements) {
        this.mapper = mapper;
        this.data = Path.of(root).resolve("data").normalize();
        this.announcements = announcements;
    }

    /** Retained for standalone controller tests. */
    HousingDataController(ObjectMapper mapper, String root) {
        this.mapper = mapper;
        this.data = Path.of(root).resolve("data").normalize();
        this.announcements = null;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", read("profile.json", new TypeReference<Map<String, Object>>() {}, Map.of()));
        result.put("programs", read("programs.json", new TypeReference<List<Object>>() {}, List.of()));
        result.put("notices", read("notices.json", new TypeReference<List<Object>>() {}, List.of()));
        result.put("monitor", read("monitor-state.json", new TypeReference<Map<String, Object>>() {}, Map.of()));
        result.put("discoveries", read("auto-discovered.json", new TypeReference<List<Object>>() {}, List.of()));
        result.put("notifications", read("notifications.json", new TypeReference<Map<String, Object>>() {}, Map.of()));
        result.put("homeAnnouncements",announcements==null?List.of():announcements.home(12));
        result.put("catalogFacets",announcements==null?Map.of():announcements.facets());
        result.put("catalogSummary",announcements==null?Map.of("itemCount",0):announcements.catalogSummary());
        return result;
    }

    @GetMapping("/announcements")
    public Map<String,Object> announcements(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,
            @RequestParam(defaultValue="") String region,@RequestParam(defaultValue="") String housingType,@RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="") String source,@RequestParam(defaultValue="") String query,@RequestParam(required=false) Long depositMaxWon,
            @RequestParam(required=false) Long rentMaxWon,@RequestParam(required=false) Double areaMin,@RequestParam(required=false) Double areaMax,
            @RequestParam(defaultValue="LATEST") String sort,@RequestParam(defaultValue="false") boolean includeClosed,@RequestParam(defaultValue="true") boolean includeUnknown,
            @RequestParam(defaultValue="false") boolean savedOnly,@RequestParam(defaultValue="false") boolean supplementalOnly,@RequestParam(name="savedId",required=false) List<String> savedIds,@RequestParam(defaultValue="false") boolean interestOnly,@RequestParam(name="interestRegion",required=false) List<String> interestRegions,
            @RequestParam(required=false) Long interestDepositMaxWon,@RequestParam(required=false) Long interestRentMaxWon,@RequestParam(required=false) Double interestAreaMin,@RequestParam(required=false) Double interestAreaMax,@RequestParam(defaultValue="true") boolean interestIncludeUnknown){
        if(page<1||size<1||size>50||negative(depositMaxWon)||negative(rentMaxWon)||negative(areaMin)||negative(areaMax)||negative(interestDepositMaxWon)||negative(interestRentMaxWon)||negative(interestAreaMin)||negative(interestAreaMax)||!finite(areaMin)||!finite(areaMax)||!finite(interestAreaMin)||!finite(interestAreaMax)||(areaMin!=null&&areaMax!=null&&areaMin>areaMax)||(interestAreaMin!=null&&interestAreaMax!=null&&interestAreaMin>interestAreaMax)||invalidSavedIds(savedIds))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"검색 조건이 올바르지 않습니다.");
        String normalizedSort="DEADLINE".equals(sort)?"DEADLINE":"LATEST";if(!"LATEST".equals(sort)&&!"DEADLINE".equals(sort))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"정렬 조건이 올바르지 않습니다.");
        if(announcements==null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"공고 저장소를 사용할 수 없습니다.");
        return announcements.page(new AnnouncementRepository.CatalogQuery(page,size,region,housingType,status,source,query,depositMaxWon,rentMaxWon,areaMin,areaMax,normalizedSort,includeClosed,includeUnknown,savedOnly,savedIds,new AnnouncementRepository.InterestQuery(interestOnly,interestRegions,interestDepositMaxWon,interestRentMaxWon,interestAreaMin,interestAreaMax,interestIncludeUnknown),supplementalOnly));
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("kakaoMapAppKey", Optional.ofNullable(System.getenv("KAKAO_MAP_APP_KEY")).orElse(""));
    }

    @GetMapping("/announcements/{id}")
    public ResponseEntity<Map<String,Object>> announcement(@PathVariable String id) {
        if (announcements == null) return ResponseEntity.notFound().build();
        Map<String,Object> item = announcements.announcement(id);
        if (item == null) return ResponseEntity.notFound().build();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("announcement", item);
        result.put("details", Optional.ofNullable(announcements.detail(id)).orElse(Map.of()));
        return ResponseEntity.ok(result);
    }

    private boolean negative(Number value){return value!=null&&value.doubleValue()<0;}
    private boolean finite(Double value){return value==null||Double.isFinite(value);}
    private boolean invalidSavedIds(List<String> values){return values!=null&&(values.size()>250||values.stream().anyMatch(value->value==null||value.isBlank()||value.length()>128));}

    @PutMapping("/profile")
    public ResponseEntity<ObjectNode> updateProfile(@RequestBody JsonNode input) throws IOException {
        ObjectNode current = readTree("profile.json");
        input.fields().forEachRemaining(entry -> {
            if (PROFILE_FIELDS.contains(entry.getKey())) current.set(entry.getKey(), entry.getValue());
        });
        current.put("lastUpdated", LocalDate.now().toString());
        write("profile.json", current);
        return ResponseEntity.ok(current);
    }

    private ObjectNode readTree(String file) throws IOException {
        Path path = data.resolve(file);
        return Files.exists(path) ? (ObjectNode) mapper.readTree(path.toFile()) : mapper.createObjectNode();
    }

    private <T> T read(String file, TypeReference<T> type, T fallback) throws IOException {
        Path path = data.resolve(file);
        if (!Files.exists(path)) {
            return fallback;
        }
        return mapper.readValue(path.toFile(), type);
    }

    private void write(String file, JsonNode value) throws IOException {
        Files.createDirectories(data);
        Path target = data.resolve(file);
        Path temp = Files.createTempFile(data, file, ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
