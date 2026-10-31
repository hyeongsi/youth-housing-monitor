package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
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
    private static final Set<String> PROFILE_FIELDS = Set.of("employmentStatus", "grossIncomeBand", "assetBand", "cashForDepositBand", "maxMonthlyRent", "maxDeposit", "homeOwnership", "maritalAndHousehold", "livesSeparatelyFromParents", "vehicleOwnership", "preferredAreas", "commuteOrSchoolCondition", "currentLeaseEnd", "earliestMoveDate", "notes");

    public HousingDataController(ObjectMapper mapper, @Value("${housing.root}") String root) {
        this.mapper = mapper;
        this.data = Path.of(root).resolve("data").normalize();
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
        Map<String, Object> catalog = read("all-announcements.json", new TypeReference<Map<String, Object>>() {}, Map.of());
        Map<String, Object> details = read("announcement-details.json", new TypeReference<Map<String, Object>>() {}, Map.of("schemaVersion", 1, "items", Map.of()));
        result.put("allAnnouncements", catalog);
        result.put("announcementDetails", details);
        // The featured area has a stricter contract than the catalog.  Keep the
        // raw catalog and optional details available for existing clients, while
        // also returning only verified, dated detail records safely joined to an
        // actual collected announcement.
        result.put("verifiedAnnouncements", verifiedAnnouncements(catalog, details));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> verifiedAnnouncements(Map<String, Object> catalog, Map<String, Object> details) {
        Object rawItems = catalog.get("items");
        Object rawDetails = details.get("items");
        if (!(rawItems instanceof List<?> items) || !(rawDetails instanceof Map<?, ?> detailItems)) return List.of();

        List<Map<String, Object>> joined = new ArrayList<>();
        for (Object rawAnnouncement : items) {
            if (!(rawAnnouncement instanceof Map<?, ?> announcement)) continue;
            Object id = announcement.get("id");
            if (id == null || !(detailItems.get(String.valueOf(id)) instanceof Map<?, ?> detail)) continue;
            if (!"VERIFIED".equals(String.valueOf(detail.get("verificationStatus")))
                    || !hasText(detail.get("verifiedAt"))
                    || !hasText(detail.get("sourceUrl"))
                    || !hasText(announcement.get("officialUrl"))) continue;
            joined.add(Map.of("announcement", new LinkedHashMap<>((Map<String, Object>) announcement),
                    "detail", new LinkedHashMap<>((Map<String, Object>) detail)));
        }
        return joined;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

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
