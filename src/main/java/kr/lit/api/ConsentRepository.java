package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.List;
import static kr.lit.api.ConsentModels.*;

@Repository
public class ConsentRepository {
    private final JdbcTemplate db;
    public ConsentRepository(JdbcTemplate db) {this.db=db;}
    public void lockUser(String userId) {
        db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",String.class,userId);
    }
    public List<ConsentRecordResponse> findConsentHistory(String userId) {
        return db.query("SELECT policy_type,policy_version,accepted_at FROM user_policy_consents WHERE user_id=? ORDER BY accepted_at DESC,policy_type,policy_version",
                (r,n)->new ConsentRecordResponse(r.getString("policy_type"),r.getString("policy_version"),r.getObject("accepted_at",OffsetDateTime.class).toInstant()),userId);
    }
    public void insertConsent(String userId,String policyType,String version,Instant acceptedAt) {
        db.update("INSERT INTO user_policy_consents(user_id,policy_type,policy_version,accepted_at) VALUES (?,?,?,?)",
                userId,policyType,version,OffsetDateTime.ofInstant(acceptedAt,ZoneOffset.UTC));
    }
}
