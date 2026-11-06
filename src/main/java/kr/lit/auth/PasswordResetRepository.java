package kr.lit.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class PasswordResetRepository {
    private final JdbcTemplate db;
    public PasswordResetRepository(JdbcTemplate db) {this.db=db;}
    public record ResetAccount(String id,String passwordHash,String email) {}
    public record ResetToken(String userId,String passwordFingerprint,OffsetDateTime expiresAt,
                             OffsetDateTime usedAt,OffsetDateTime invalidatedAt) {}
    public Optional<ResetAccount> lockAccountByEmail(String email) {
        return db.query("SELECT id,password_hash,email FROM app_users WHERE email=? AND user_status='ACTIVE' AND user_role='TEACHER' FOR UPDATE",
                (r,n)->new ResetAccount(r.getString("id"),r.getString("password_hash"),r.getString("email")),email).stream().findFirst();
    }
    public Optional<ResetAccount> lockAccountById(String id) {
        return db.query("SELECT id,password_hash,email FROM app_users WHERE id=? AND user_status='ACTIVE' AND user_role='TEACHER' FOR UPDATE",
                (r,n)->new ResetAccount(r.getString("id"),r.getString("password_hash"),r.getString("email")),id).stream().findFirst();
    }
    public Optional<String> getTokenUserId(String hash) {
        return db.queryForList("SELECT user_id FROM password_reset_tokens WHERE token_hash=?",String.class,hash).stream().findFirst();
    }
    public Optional<ResetToken> lockToken(String hash) {
        return db.query("SELECT * FROM password_reset_tokens WHERE token_hash=? FOR UPDATE",(r,n)->new ResetToken(
                r.getString("user_id"),r.getString("password_fingerprint"),r.getObject("expires_at",OffsetDateTime.class),
                r.getObject("used_at",OffsetDateTime.class),r.getObject("invalidated_at",OffsetDateTime.class)),hash).stream().findFirst();
    }
    public boolean hasRecentRequest(String userId,OffsetDateTime cutoff) {
        return db.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id=? AND created_at>?",Integer.class,userId,cutoff)>0;
    }
    public void invalidateTokens(String userId,OffsetDateTime now) {
        db.update("UPDATE password_reset_tokens SET invalidated_at=? WHERE user_id=? AND used_at IS NULL AND invalidated_at IS NULL",now,userId);
    }
    public void saveToken(String hash,ResetAccount account,OffsetDateTime now,OffsetDateTime expiresAt) {
        db.update("INSERT INTO password_reset_tokens(token_hash,user_id,password_fingerprint,created_at,expires_at) VALUES (?,?,?,?,?)",
                hash,account.id(),AuthService.hash(account.passwordHash()+"\n"+account.email()),now,expiresAt);
    }
    public void consumeToken(String hash,OffsetDateTime now) {
        db.update("UPDATE password_reset_tokens SET used_at=? WHERE token_hash=?",now,hash);
    }
    public void updatePassword(String userId,String hash,OffsetDateTime now) {
        db.update("UPDATE app_users SET password_hash=?,failed_logins=0,locked_until=NULL,updated_at=? WHERE id=?",hash,now,userId);
        db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?",now,userId);
    }
}
