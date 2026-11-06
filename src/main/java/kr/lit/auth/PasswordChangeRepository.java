package kr.lit.auth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.List;
@Repository
public class PasswordChangeRepository {
    private final JdbcTemplate db;
    public PasswordChangeRepository(JdbcTemplate db) {this.db=db;}
    public List<String> lockPassword(String userId) {return db.queryForList("SELECT password_hash FROM app_users WHERE id=? AND user_status='ACTIVE' FOR UPDATE",String.class,userId);}
    public List<String> getSessionFamily(String userId,String sessionId) {return db.queryForList("SELECT family_id FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND access_expires_at>CURRENT_TIMESTAMP",String.class,sessionId,userId);}
    public void updatePassword(String userId,String hash) {db.update("UPDATE app_users SET password_hash=?,updated_at=? WHERE id=?",hash,OffsetDateTime.now(ZoneOffset.UTC),userId);}
    public void revokeOtherSessions(String userId,String familyId) {db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=? AND family_id<>?",OffsetDateTime.now(ZoneOffset.UTC),userId,familyId);}
}
