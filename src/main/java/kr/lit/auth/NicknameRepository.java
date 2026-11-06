package kr.lit.auth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
@Repository
public class NicknameRepository {
    private final JdbcTemplate db;
    public NicknameRepository(JdbcTemplate db) {this.db=db;}
    public boolean lockAccount(String userId) {return !db.queryForList("SELECT id FROM app_users WHERE id=? AND user_status='ACTIVE' FOR UPDATE",userId).isEmpty();}
    public boolean existsSession(String userId,String sessionId) {return db.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND access_expires_at>CURRENT_TIMESTAMP",Integer.class,sessionId,userId)>0;}
    public void updateNickname(String userId,String nickname) {db.update("UPDATE app_users SET display_name=?,updated_at=? WHERE id=?",nickname,OffsetDateTime.now(java.time.ZoneOffset.UTC),userId);}
}
