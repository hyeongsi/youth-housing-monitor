package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class MobileDataService {
    private final ObjectMapper mapper;
    private final Path data;
    public MobileDataService(ObjectMapper mapper, @Value("${housing.root}") String root) { this.mapper = mapper; this.data = Path.of(root).resolve("data").normalize(); }

    public Map<String, Object> catalog() { return read("all-announcements.json", new TypeReference<>() {}, Map.of("items", List.of())); }
    public Map<String, Object> details() { return read("announcement-details.json", new TypeReference<>() {}, Map.of("schemaVersion", 1, "items", Map.of())); }
    @SuppressWarnings("unchecked") public Map<String, Object> announcement(String id) {
        Map<String,Object> found = ((List<Object>) catalog().getOrDefault("items", List.of())).stream().filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value).filter(item -> id.equals(String.valueOf(item.get("id")))).findFirst().orElse(null);
        return found;
    }
    private <T> T read(String name, TypeReference<T> type, T fallback) { try { Path file = data.resolve(name); return Files.exists(file) ? mapper.readValue(file.toFile(), type) : fallback; } catch (Exception ignored) { return fallback; } }
}
