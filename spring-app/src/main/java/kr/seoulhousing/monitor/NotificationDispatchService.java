package kr.seoulhousing.monitor;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class NotificationDispatchService {
    private final NotificationStore store; private final PushSender sender;
    public NotificationDispatchService(NotificationStore store, PushSender sender) { this.store=store; this.sender=sender; }
    @SuppressWarnings("unchecked") public void dispatch(Map<String,Object> refreshResult) {
        Object raw=refreshResult.get("newItems"); if (!(raw instanceof List<?> candidates)) return;
        for (Object value : candidates) if (value instanceof Map<?,?> rawItem) dispatchItem((Map<String,Object>) rawItem);
    }
    public void dispatchItem(Map<String,Object> item) {
        String announcementId=String.valueOf(item.get("id"));
        if (store.eventExists(announcementId) || !matches(item,store.conditions())) return;
        store.event(announcementId);
        String title=String.valueOf(item.getOrDefault("title","새 주거 공고")); String body="새 공고가 등록되었습니다. 공고문에서 상세 조건을 확인하세요.";
        for (Map<String,Object> device:store.activeDevices()) {
            String deviceId=String.valueOf(device.get("id")); if (!store.reserveDelivery(announcementId,deviceId)) continue;
            PushSender.PushResult result=sender.send(String.valueOf(device.get("fcm_token")),title,body,Map.of("announcementId",announcementId));
            store.finishDelivery(announcementId,deviceId,result.sent()?"SENT":"FAILED",result.error());
            if(result.sent()) store.inbox(announcementId,deviceId,title,body);
            if(result.invalidToken()) store.deactivate(deviceId);
        }
    }
    boolean matches(Map<String,Object> item, Map<String,List<String>> condition) {
        return includes(condition.get("regions"), join(item,"area","location","district"))
                && includes(condition.get("housingTypes"),String.valueOf(item.getOrDefault("housingType", "")))
                && includes(condition.get("statuses"),String.valueOf(item.getOrDefault("status", "")))
                && includes(condition.get("keywords"),String.valueOf(item.getOrDefault("title", "")));
    }
    private boolean includes(List<String> terms,String actual) { if(terms==null||terms.isEmpty()) return true; String value=actual.toLowerCase(Locale.ROOT); return terms.stream().filter(Objects::nonNull).map(term->term.trim().toLowerCase(Locale.ROOT)).filter(term->!term.isEmpty()).anyMatch(value::contains); }
    private String join(Map<String,Object> item,String... keys) { StringBuilder value=new StringBuilder(); for(String key:keys) if(item.get(key)!=null) value.append(' ').append(item.get(key)); return value.toString(); }
}
