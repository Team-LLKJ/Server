package kr.lit.api;

import kr.lit.auth.AccountAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.List;

/**
 * 교사 알림. 학생은 계정이 없으므로 알림 대상은 평가 소유 교사뿐입니다.
 * notify()는 호출한 트랜잭션에 참여하며 실패해도 예외를 밖으로 던지지 않습니다(제출·종료가 알림 때문에 실패하지 않도록).
 */
@Service
public class NotificationService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NotificationService.class);
    public enum Type { SUBMISSION, EVALUATION_ENDED, WARNING, ANALYSIS_DONE }
    public record Notification(long id,String type,String title,String body,String evaluationId,String participantId,
                               Instant createdAt,boolean read,Instant readAt) {}
    public record Page(List<Notification> items,int page,int size,long totalElements,long totalPages,long unreadCount) {}
    public record UnreadCount(long unreadCount) {}
    public record ReadAll(long updated,long unreadCount) {}
    public record Settings(@com.fasterxml.jackson.annotation.JsonProperty("notify") boolean notifyEnabled,boolean sound,Instant updatedAt) {}

    private final JdbcTemplate db;
    public NotificationService(JdbcTemplate db) { this.db=db; }

    private static Instant instant(ResultSet r,String column) throws SQLException {
        var v=r.getObject(column,OffsetDateTime.class);return v==null?null:v.toInstant();
    }
    private Notification row(ResultSet r,int n) throws SQLException {
        Instant read=instant(r,"read_at");
        return new Notification(r.getLong("id"),r.getString("notification_type"),r.getString("title"),r.getString("body"),
                r.getString("evaluation_id"),r.getString("participant_id"),instant(r,"created_at"),read!=null,read);
    }

    /** 평가 소유 교사에게 알림을 남깁니다. dedupeKey가 같으면 한 번만 저장합니다. */
    public void notifyOwner(String evaluationId,Type type,String title,String body,String participantId,String dedupeKey) {
        try {
            var owners=db.queryForList("SELECT teacher_id FROM evaluations WHERE id=?",String.class,evaluationId);
            if(owners.isEmpty()||owners.get(0)==null) return;
            String userId=owners.get(0);
            var enabled=db.queryForList("SELECT notify FROM user_settings WHERE user_id=?",Boolean.class,userId);
            if(!enabled.isEmpty()&&!enabled.get(0)) return;
            if(dedupeKey!=null&&db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND dedupe_key=?",Integer.class,userId,dedupeKey)>0) return;
            db.update("INSERT INTO notifications(user_id,notification_type,title,body,evaluation_id,participant_id,dedupe_key,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    userId,type.name(),cut(title,200),cut(body,1000),evaluationId,participantId,dedupeKey,OffsetDateTime.now(ZoneOffset.UTC));
        } catch(RuntimeException ex) {
            log.warn("Notification skipped: evaluation={}, type={}, error={}",evaluationId,type,ex.getClass().getSimpleName());
        }
    }
    private static String cut(String v,int max) { return v==null?"":v.length()<=max?v:v.substring(0,max); }

    private String me() { return AccountAccess.require(kr.lit.auth.AuthModels.Role.TEACHER).userId(); }
    private long unread(String userId) {
        return db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND read_at IS NULL",Long.class,userId);
    }

    @Transactional(readOnly=true)
    public Page list(int page,int size,boolean unreadOnly) {
        String userId=me();
        if(page<0||size<1||size>100) throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","page는 0 이상, size는 1~100이어야 합니다.");
        String filter=" FROM notifications WHERE user_id=?"+(unreadOnly?" AND read_at IS NULL":"");
        long total=db.queryForObject("SELECT COUNT(*)"+filter,Long.class,userId);
        var items=db.query("SELECT *"+filter+" ORDER BY id DESC LIMIT ? OFFSET ?",this::row,userId,size,(long)page*size);
        return new Page(items,page,size,total,(total+size-1)/size,unread(userId));
    }
    @Transactional(readOnly=true)
    public UnreadCount unreadCount() { return new UnreadCount(unread(me())); }
    @Transactional
    public Notification read(long id) {
        String userId=me();
        var rows=db.query("SELECT * FROM notifications WHERE id=? AND user_id=?",this::row,id,userId);
        if(rows.isEmpty()) throw new ApiError.Failure(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","알림을 찾을 수 없습니다.");
        db.update("UPDATE notifications SET read_at=COALESCE(read_at,?) WHERE id=?",OffsetDateTime.now(ZoneOffset.UTC),id);
        return db.query("SELECT * FROM notifications WHERE id=?",this::row,id).get(0);
    }
    @Transactional
    public ReadAll readAll() {
        String userId=me();
        long updated=db.update("UPDATE notifications SET read_at=? WHERE user_id=? AND read_at IS NULL",OffsetDateTime.now(ZoneOffset.UTC),userId);
        return new ReadAll(updated,unread(userId));
    }
    @Transactional(readOnly=true)
    public Settings settings() {
        String userId=me();
        var rows=db.query("SELECT * FROM user_settings WHERE user_id=?",(r,n)->new Settings(r.getBoolean("notify"),r.getBoolean("sound"),instant(r,"updated_at")),userId);
        return rows.isEmpty()?new Settings(true,true,null):rows.get(0);
    }
    @Transactional
    public Settings saveSettings(Boolean notify,Boolean sound) {
        String userId=me();db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",String.class,userId);var current=settings();
        boolean n=notify==null?current.notifyEnabled():notify,s=sound==null?current.sound():sound;
        db.update("MERGE INTO user_settings(user_id,notify,sound,updated_at) KEY(user_id) VALUES (?,?,?,?)",userId,n,s,OffsetDateTime.now(ZoneOffset.UTC));
        return settings();
    }
}
