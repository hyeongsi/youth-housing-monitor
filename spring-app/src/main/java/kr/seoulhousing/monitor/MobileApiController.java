package kr.seoulhousing.monitor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Token-authenticated API for the paired personal Android device. */
@RestController
@RequestMapping("/api/mobile")
public class MobileApiController {
    private final NotificationStore store; private final MobileDataService data; private final Duration pairingTtl; private final SecureRandom random=new SecureRandom();
    public MobileApiController(NotificationStore store, MobileDataService data,
                               @org.springframework.beans.factory.annotation.Value("${housing.push.pairing-code-ttl:PT10M}") Duration pairingTtl) { this.store=store; this.data=data; this.pairingTtl=pairingTtl; }

    @PostMapping("/pairing-codes") public Map<String,Object> pairingCode() {
        String code=String.format("%06d",random.nextInt(1_000_000)); Instant expires=Instant.now().plus(pairingTtl); store.savePairingCode(code,expires);
        return Map.of("code",code,"expiresAt",expires.toString());
    }
    @PostMapping("/pair") public ResponseEntity<?> pair(@RequestBody PairRequest request) {
        if(request.code()==null || !request.code().matches("\\d{6}") || !store.consumePairingCode(request.code())) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","유효하지 않거나 만료된 페어링 코드입니다."));
        String deviceId=UUID.randomUUID().toString(), accessToken=UUID.randomUUID()+"-"+UUID.randomUUID(); store.saveDevice(deviceId,clean(request.deviceName(),"Android"),accessToken,request.fcmToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("deviceId",deviceId,"accessToken",accessToken));
    }
    @PutMapping("/device/token") public ResponseEntity<?> token(@RequestHeader(value="X-Device-Id",required=false) String deviceId,@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody TokenRequest request) { if(!authorized(deviceId,authorization)) return denied(); store.updateToken(deviceId,request.fcmToken()); return ResponseEntity.noContent().build(); }
    @DeleteMapping("/device") public ResponseEntity<?> unregister(@RequestHeader(value="X-Device-Id",required=false) String deviceId,@RequestHeader(value="Authorization",required=false) String authorization) { if(!authorized(deviceId,authorization)) return denied(); store.deactivate(deviceId); return ResponseEntity.noContent().build(); }
    @GetMapping("/conditions") public ResponseEntity<?> conditions(@RequestHeader(value="X-Device-Id",required=false) String deviceId,@RequestHeader(value="Authorization",required=false) String authorization) { if(!authorized(deviceId,authorization)) return denied(); return ResponseEntity.ok(store.conditions()); }
    @PutMapping("/conditions") public ResponseEntity<?> conditions(@RequestHeader(value="X-Device-Id",required=false) String deviceId,@RequestHeader(value="Authorization",required=false) String authorization,@RequestBody ConditionsRequest request) { if(!authorized(deviceId,authorization)) return denied(); Map<String,List<String>> value=Map.of("regions",clean(request.regions()),"housingTypes",clean(request.housingTypes()),"statuses",clean(request.statuses()),"keywords",clean(request.keywords())); store.conditions(value); return ResponseEntity.ok(value); }
    @GetMapping("/inbox") public ResponseEntity<?> inbox(@RequestHeader(value="X-Device-Id",required=false) String deviceId,@RequestHeader(value="Authorization",required=false) String authorization) { if(!authorized(deviceId,authorization)) return denied(); return ResponseEntity.ok(Map.of("items",store.inbox(deviceId))); }
    @PostMapping("/inbox/{id}/read") public ResponseEntity<?> read(@RequestHeader(value="X-Device-Id",required=false) String deviceId,@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable long id) { if(!authorized(deviceId,authorization)) return denied(); return store.markRead(deviceId,id)?ResponseEntity.noContent().build():ResponseEntity.notFound().build(); }
    @GetMapping("/announcements") public ResponseEntity<?> announcements(@RequestHeader(value="X-Device-Id",required=false) String deviceId,@RequestHeader(value="Authorization",required=false) String authorization) { if(!authorized(deviceId,authorization)) return denied(); return ResponseEntity.ok(Map.of("catalog",data.catalog(),"announcementDetails",data.details())); }
    @GetMapping("/announcements/{id}") public ResponseEntity<?> announcement(@RequestHeader(value="X-Device-Id",required=false) String deviceId,@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable String id) { if(!authorized(deviceId,authorization)) return denied(); Map<String,Object> announcement=data.announcement(id); if(announcement==null) return ResponseEntity.notFound().build(); Object details=((Map<?,?>) data.details().getOrDefault("items",Map.of())).get(id); Map<String,Object> output=new LinkedHashMap<>(); output.put("announcement",announcement); output.put("details",details==null?Map.of():details); return ResponseEntity.ok(output); }
    private boolean authorized(String id,String header) { return id!=null&&header!=null&&header.startsWith("Bearer ")&&store.authenticated(id,header.substring(7)); }
    private ResponseEntity<Map<String,String>> denied() { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","페어링된 기기 인증이 필요합니다.")); }
    private List<String> clean(List<String> values) { return values==null?List.of():values.stream().filter(Objects::nonNull).map(String::trim).filter(value->!value.isBlank()).limit(50).toList(); }
    private String clean(String value,String fallback) { return value==null||value.isBlank()?fallback:value.trim().substring(0,Math.min(value.trim().length(),100)); }
    public record PairRequest(String code,String deviceName,String fcmToken) {} public record TokenRequest(String fcmToken) {} public record ConditionsRequest(List<String> regions,List<String> housingTypes,List<String> statuses,List<String> keywords) {}
}
