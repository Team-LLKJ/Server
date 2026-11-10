package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;

@Repository
public class NotificationRepository {
    private final JdbcTemplate db;
    public NotificationRepository(JdbcTemplate db) {this.db=db;}
    static String getVisiblePredicate() {
        return "(notifications.deleted_at IS NULL AND (assessment_id IS NULL OR EXISTS (SELECT 1 FROM assessment_templates a WHERE a.id=notifications.assessment_id AND (a.teacher_id=notifications.user_id OR EXISTS (SELECT 1 FROM assessment_collaborators c WHERE c.assessment_id=a.id AND c.teacher_id=notifications.user_id)))))";
    }
    public int deleteNotification(String userId,long id,OffsetDateTime now) {
        return db.update("UPDATE notifications SET deleted_at=? WHERE user_id=? AND id=? AND "+getVisiblePredicate(),now,userId,id);
    }
    public int deleteNotifications(String userId,OffsetDateTime now) {
        return db.update("UPDATE notifications SET deleted_at=? WHERE user_id=? AND "+getVisiblePredicate(),now,userId);
    }
}
