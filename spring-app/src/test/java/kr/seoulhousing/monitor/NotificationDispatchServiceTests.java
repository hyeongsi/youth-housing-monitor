package kr.seoulhousing.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NotificationDispatchServiceTests {
    @TempDir Path temp;

    @Test void matchingNewAnnouncementIsSentOnlyOncePerDevice() {
        Fixture fixture=new Fixture(PushSender.PushResult.success()); fixture.pair(); fixture.store.conditions(Map.of("regions",List.of("관악구"),"housingTypes",List.of("행복주택"),"statuses",List.of("접수중"),"keywords",List.of("청년")));
        Map<String,Object> item=item("new-1","관악구 청년 행복주택","행복주택","관악구","접수중");
        fixture.service.dispatch(Map.of("newItems",List.of(item)));
        fixture.service.dispatch(Map.of("newItems",List.of(item)));
        assertEquals(1,fixture.calls.get()); assertEquals(1,fixture.store.inbox("device").size());
    }

    @Test void announcementOutsideConditionsIsNotSent() {
        Fixture fixture=new Fixture(PushSender.PushResult.success()); fixture.pair(); fixture.store.conditions(Map.of("regions",List.of("관악구"),"housingTypes",List.of(),"statuses",List.of(),"keywords",List.of()));
        fixture.service.dispatch(Map.of("newItems",List.of(item("new-2","강남구 공고","매입임대","강남구","접수중"))));
        assertEquals(0,fixture.calls.get()); assertTrue(fixture.store.inbox("device").isEmpty());
    }

    @Test void failedFcmDeliveryIsRecordedAndInvalidTokenIsDeactivated() {
        Fixture fixture=new Fixture(PushSender.PushResult.failed("FCM UNREGISTERED",true)); fixture.pair();
        fixture.service.dispatch(Map.of("newItems",List.of(item("new-3","청년 공고","행복주택","관악구","접수중"))));
        assertEquals("FAILED",fixture.jdbc.queryForObject("SELECT status FROM notification_deliveries WHERE announcement_id='new-3'",String.class));
        assertFalse(fixture.store.authenticated("device","access"));
    }

    @Test void tokenCanBeReplacedAndDeviceCanBeUnregistered() {
        Fixture fixture=new Fixture(PushSender.PushResult.success()); fixture.pair(); fixture.store.updateToken("device","replacement-token");
        assertEquals("replacement-token",fixture.store.activeDevices().getFirst().get("fcm_token")); fixture.store.deactivate("device"); assertTrue(fixture.store.activeDevices().isEmpty());
    }

    private Map<String,Object> item(String id,String title,String type,String area,String status) { return Map.of("id",id,"title",title,"housingType",type,"area",area,"status",status); }
    private final class Fixture {
        final JdbcTemplate jdbc; final NotificationStore store; final AtomicInteger calls=new AtomicInteger(); final NotificationDispatchService service;
        Fixture(PushSender.PushResult result) { jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("push.db"),"","")); store=new NotificationStore(jdbc,new ObjectMapper()); service=new NotificationDispatchService(store,(token,title,body,data)->{calls.incrementAndGet();return result;}); }
        void pair() { store.saveDevice("device","Android","access","token"); }
    }
}
