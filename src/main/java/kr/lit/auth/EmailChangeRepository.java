package kr.lit.auth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;
@Repository
public class EmailChangeRepository {
    private final JdbcTemplate db;
    public EmailChangeRepository(JdbcTemplate db) {this.db=db;}
    public List<Map<String,Object>> lockAccount(String userId) {return db.queryForList("SELECT * FROM app_users WHERE id=? AND user_status='ACTIVE' FOR UPDATE",userId);}
    public boolean existsSession(String userId,String sessionId) {return db.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND access_expires_at>CURRENT_TIMESTAMP",Integer.class,sessionId,userId)>0;}
    public boolean existsEmail(String email) {return db.queryForObject("SELECT COUNT(*) FROM app_users WHERE email=?",Integer.class,email)>0;}
    public List<Map<String,Object>> getChallenge(String userId) {return db.queryForList("SELECT * FROM email_change_verifications WHERE user_id=?",userId);}
    public void saveChallenge(String userId,String email,String codeHash,String passwordHash,OffsetDateTime now) {
        db.update("MERGE INTO email_change_verifications(user_id,email,code_hash,password_hash,last_sent_at,expires_at,attempts) KEY(user_id) VALUES (?,?,?,?,?,?,0)",userId,email,codeHash,passwordHash,now,now.plusMinutes(10));
    }
    public void incrementAttempts(String userId) {db.update("UPDATE email_change_verifications SET attempts=attempts+1 WHERE user_id=?",userId);}
    public void updateEmail(String userId,String email,OffsetDateTime now) {db.update("UPDATE app_users SET email=?,updated_at=? WHERE id=?",email,now,userId);}
    public void deleteChallenge(String userId) {db.update("DELETE FROM email_change_verifications WHERE user_id=?",userId);}
    public void invalidateSignupVerification(String email) {db.update("DELETE FROM signup_email_verifications WHERE email=?",email);}
}
