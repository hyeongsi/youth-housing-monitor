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
        result.put("profile", read("profile.json", new TypeReference<Map<String, Object>>() {}));
        result.put("programs", read("programs.json", new TypeReference<List<Object>>() {}));
        result.put("notices", read("notices.json", new TypeReference<List<Object>>() {}));
        result.put("monitor", read("monitor-state.json", new TypeReference<Object>() {}));
        result.put("discoveries", read("auto-discovered.json", new TypeReference<List<Object>>() {}));
        result.put("notifications", read("notifications.json", new TypeReference<Object>() {}));
        result.put("allAnnouncements", read("all-announcements.json", new TypeReference<Map<String, Object>>() {}));
        return result;
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

    private <T> T read(String file, TypeReference<T> type) throws IOException {
        Path path = data.resolve(file);
        if (!Files.exists(path)) {
            return mapper.convertValue(Map.of(), type);
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
