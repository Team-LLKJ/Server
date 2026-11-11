package kr.lit.auth;

import kr.lit.db.DatabaseUpsert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.Map;
@Repository
public class SignupEmailRepository {
    private final JdbcTemplate db;
    public SignupEmailRepository(JdbcTemplate db) {this.db=db;}
    public boolean existsEmail(String email) {return db.queryForObject("SELECT COUNT(*) FROM app_users WHERE email=?",Integer.class,email)>0;}
    public boolean existsUsername(String username) {return db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?",Integer.class,username)>0;}
    public Map<String,Object> lockVerification(String email) {
        // MERGE updates only the key and preserves existing verification fields.
        DatabaseUpsert.save(db,"signup_email_verifications","email","email","?",email);
        return db.queryForMap("SELECT * FROM signup_email_verifications WHERE email=? FOR UPDATE",email);
    }
    public void saveChallenge(String email,String hash,OffsetDateTime sent,OffsetDateTime expires) {
        db.update("UPDATE signup_email_verifications SET code_hash=?,last_sent_at=?,code_expires_at=?,attempts=0,verified_at=NULL,token_hash=NULL,token_expires_at=NULL,used_at=NULL WHERE email=?",hash,sent,expires,email);
    }
    public void incrementAttempts(String email) {db.update("UPDATE signup_email_verifications SET attempts=attempts+1 WHERE email=?",email);}
    public void saveConfirmation(String email,String tokenHash,OffsetDateTime now,OffsetDateTime expires) {
        db.update("UPDATE signup_email_verifications SET verified_at=?,token_hash=?,token_expires_at=?,code_hash=NULL WHERE email=?",now,tokenHash,expires,email);
    }
    public void consumeToken(String email,OffsetDateTime now) {db.update("UPDATE signup_email_verifications SET used_at=? WHERE email=?",now,email);}
}
