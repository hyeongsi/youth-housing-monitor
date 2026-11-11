package kr.seoulhousing.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class MobileDataService {
    private final ObjectMapper mapper;
    private final Path data;
    private final AnnouncementRepository announcements;
    @Autowired public MobileDataService(ObjectMapper mapper, @Value("${housing.root}") String root, AnnouncementRepository announcements) { this.mapper = mapper; this.data = Path.of(root).resolve("data").normalize(); this.announcements=announcements; }
    MobileDataService(ObjectMapper mapper, String root) { this.mapper=mapper; this.data=Path.of(root).resolve("data").normalize(); this.announcements=null; }

    public Map<String, Object> catalog() { return announcements == null ? read("all-announcements.json", new TypeReference<>() {}, Map.of("items", List.of())) : announcements.catalog(); }
    public Map<String, Object> details() { return announcements == null ? read("announcement-details.json", new TypeReference<>() {}, Map.of("schemaVersion", 1, "items", Map.of())) : announcements.details(); }
    @SuppressWarnings("unchecked") public Map<String, Object> announcement(String id) {
        if (announcements != null) return announcements.announcement(id);
        Map<String,Object> found = ((List<Object>) catalog().getOrDefault("items", List.of())).stream().filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value).filter(item -> id.equals(String.valueOf(item.get("id")))).findFirst().orElse(null);
        return found;
    }
    public Map<String,Object> detail(String id) {
        if (announcements != null) return announcements.detail(id);
        Object value=((Map<?,?>)details().getOrDefault("items",Map.of())).get(id);
        if (!(value instanceof Map<?,?> raw)) return null;
        Map<String,Object> detail=new java.util.LinkedHashMap<>(); raw.forEach((key,item)->detail.put(String.valueOf(key),item)); return detail;
    }
    private <T> T read(String name, TypeReference<T> type, T fallback) { try { Path file = data.resolve(name); return Files.exists(file) ? mapper.readValue(file.toFile(), type) : fallback; } catch (Exception ignored) { return fallback; } }
}
