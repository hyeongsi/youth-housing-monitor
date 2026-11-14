package kr.seoulhousing.monitor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api")
class AccountController {
    private final UserAccountRepository users; private final AnnouncementRepository announcements; private final boolean googleEnabled;
    AccountController(UserAccountRepository users,AnnouncementRepository announcements,@Value("${housing.auth.google.client-id:}") String clientId,@Value("${housing.auth.google.client-secret:}") String clientSecret){this.users=users;this.announcements=announcements;this.googleEnabled=!clientId.isBlank()&&!clientSecret.isBlank();}

    @GetMapping("/auth/csrf") Map<String,Object> csrf(CsrfToken token){return Map.of("token",token.getToken());}
    @GetMapping("/auth/config") Map<String,Object> config(){return Map.of("googleEnabled",googleEnabled);}
    @GetMapping("/me") Map<String,Object> me(Authentication authentication){Map<String,Object> user=current(authentication,false);return user==null?Map.of("authenticated",false,"googleEnabled",googleEnabled):Map.of("authenticated",true,"googleEnabled",googleEnabled,"user",user);}
    @PostMapping("/me/logout") ResponseEntity<Void> logout(HttpServletRequest request,HttpServletResponse response,Authentication authentication){new SecurityContextLogoutHandler().logout(request,response,authentication);return ResponseEntity.noContent().build();}
    @GetMapping("/me/saved-announcements") Map<String,Object> saved(Authentication authentication){return Map.of("items",users.saved(userId(authentication)));}
    @PutMapping("/me/saved-announcements/{announcementId}") ResponseEntity<Void> save(Authentication authentication,@PathVariable String announcementId,@RequestBody(required=false) Map<String,Object> input){if(announcements.announcement(announcementId)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"공개 중인 공고를 찾을 수 없습니다.");String note=limit(input==null?null:input.get("note"),300),reminder=limit(input==null?null:input.get("reminderAt"),40);users.save(userId(authentication),announcementId,note,reminder);return ResponseEntity.noContent().build();}
    @DeleteMapping("/me/saved-announcements/{announcementId}") ResponseEntity<Void> remove(Authentication authentication,@PathVariable String announcementId){users.remove(userId(authentication),announcementId);return ResponseEntity.noContent().build();}
    @PutMapping("/me/saved-announcements/order") ResponseEntity<Void> reorder(Authentication authentication,@RequestBody List<String> ids){if(ids.size()>250||ids.stream().anyMatch(id->id==null||id.isBlank()||id.length()>128))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"저장 순서가 올바르지 않습니다.");users.reorder(userId(authentication),ids.stream().distinct().toList());return ResponseEntity.noContent().build();}
    @PostMapping("/me/migrate-local-state") Map<String,Object> migrate(Authentication authentication,@RequestBody Map<String,Object> input){List<Map<String,Object>> items=new ArrayList<>();Object value=input.get("items");if(value instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> raw){Map<String,Object> mapped=new LinkedHashMap<>();raw.forEach((key,entry)->mapped.put(String.valueOf(key),entry));String id=String.valueOf(mapped.getOrDefault("announcementId","")).trim();if(!id.isBlank()&&announcements.announcement(id)!=null)items.add(mapped);}if(items.size()>250)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"이전할 관심 공고가 너무 많습니다.");users.merge(userId(authentication),items);return Map.of("mergedCount",items.size(),"items",users.saved(userId(authentication)));}
    private String userId(Authentication authentication){Map<String,Object> user=current(authentication,true);return String.valueOf(user.get("id"));}
    private Map<String,Object> current(Authentication authentication,boolean required){if(authentication!=null&&authentication.getPrincipal() instanceof OAuth2User principal){String subject=String.valueOf(principal.getAttribute("sub"));if(!subject.isBlank()&& !"null".equals(subject)){users.upsertGoogle(subject,String.valueOf(principal.getAttribute("email")),String.valueOf(principal.getAttribute("name")));return users.user(subject);}}if(required)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"로그인이 필요합니다.");return null;}
    private String limit(Object value,int maximum){String text=value==null?"":String.valueOf(value).trim();return text.length()>maximum?text.substring(0,maximum):text;}
}
