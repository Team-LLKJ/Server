package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotificationDeletionApiTest extends AuthTestSupport {
    @Autowired NotificationService notifications;
    long createNotification(String userId,String key) {
        db.update("INSERT INTO notifications(user_id,notification_type,title,body,dedupe_key,created_at) VALUES (?,'WARNING','알림','본문',?,CURRENT_TIMESTAMP)",userId,key);
        return db.queryForObject("SELECT id FROM notifications WHERE user_id=? AND dedupe_key=?",Long.class,userId,key);
    }
    @Test void deleteRemovesReadAndUnreadViewsAndBlocksReadingAgain() throws Exception {
        long id=createNotification(teacher.user().id(),unique());
        createNotification(teacher.user().id(),unique());
        mvc.perform(request(delete("/api/notifications/"+id),teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("deleted").value(1)).andExpect(jsonPath("unreadCount").value(1));
        for(String path:new String[]{"/api/notifications","/api/notifications?unreadOnly=true"}) {
            var page=ok(request(get(path),teacher));
            assertThat(page.path("items").size()).isEqualTo(1);
            assertThat(page.path("items").get(0).path("id").asLong()).isNotEqualTo(id);
        }
        mvc.perform(request(patch("/api/notifications/"+id+"/read"),teacher)).andExpect(status().isNotFound());
        mvc.perform(request(delete("/api/notifications/"+id),teacher)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOTIFICATION_NOT_FOUND"));
        assertThat(db.queryForObject("SELECT deleted_at IS NOT NULL FROM notifications WHERE id=?",Boolean.class,id)).isTrue();
        mvc.perform(request(post("/api/notifications/read-all"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("updated").value(1));
    }
    @Test void deleteAllAffectsOnlyCurrentTeacherAndIsRepeatable() throws Exception {
        var other=newTeacher();long read=createNotification(teacher.user().id(),unique());
        createNotification(teacher.user().id(),unique());createNotification(other.user().id(),unique());
        ok(request(patch("/api/notifications/"+read+"/read"),teacher));
        mvc.perform(request(delete("/api/notifications"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("deleted").value(2)).andExpect(jsonPath("unreadCount").value(0));
        mvc.perform(request(delete("/api/notifications"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("deleted").value(0));
        assertThat(ok(request(get("/api/notifications"),teacher)).path("totalElements").asLong()).isZero();
        assertThat(ok(request(get("/api/notifications"),other)).path("totalElements").asLong()).isEqualTo(1);
    }
    @Test void unauthorizedAndOtherTeachersCannotDeleteNotifications() throws Exception {
        long id=createNotification(teacher.user().id(),unique());var other=newTeacher();
        mvc.perform(request(delete("/api/notifications/"+id),other)).andExpect(status().isNotFound());
        for(String path:new String[]{"/api/notifications","/api/notifications/"+id})
            mvc.perform(request(delete(path),null)).andExpect(status().isUnauthorized());
        assertThat(ok(request(get("/api/notifications"),teacher)).path("totalElements").asLong()).isEqualTo(1);
    }
    @Test void deletionRetainsDedupeKeySoSameEventCannotReappear() throws Exception {
        as(teacher);var evaluation=evaluations.create("알림 중복 검증",60);String key="event:"+unique();
        notifications.notifyOwner(evaluation.id(),NotificationService.Type.WARNING,"경고","본문",null,key);
        long id=db.queryForObject("SELECT id FROM notifications WHERE user_id=? AND dedupe_key=?",Long.class,teacher.user().id(),key);
        ok(request(delete("/api/notifications/"+id),teacher));
        notifications.notifyOwner(evaluation.id(),NotificationService.Type.WARNING,"경고","본문",null,key);
        assertThat(ok(request(get("/api/notifications"),teacher)).path("items").size()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND dedupe_key=?",Integer.class,teacher.user().id(),key)).isEqualTo(1);
    }
    @Test void revokedSharingNoticeCannotBeDeletedOrCountedByBulkDeletion() throws Exception {
        long id=createNotification(teacher.user().id(),unique());
        as(teacher);var template=newTeacher();
        // 소유자·공유자가 아닌 다른 틀의 알림은 기존 가시성 정책으로 숨깁니다.
        String templateId=java.util.UUID.randomUUID().toString();
        db.update("INSERT INTO assessment_templates(id,teacher_id,title,guidelines,ai_policy,criteria_json,anti_cheat_json,questions_json,created_at,updated_at) VALUES (?,?,'타인 틀','','DISABLED','[]','{}','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",templateId,template.user().id());
        db.update("UPDATE notifications SET assessment_id=? WHERE id=?",templateId,id);
        mvc.perform(request(delete("/api/notifications/"+id),teacher)).andExpect(status().isNotFound());
        mvc.perform(request(delete("/api/notifications"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("deleted").value(0));
    }
}
