package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

@Repository
public class ParticipantSessionRepository {
    private final JdbcTemplate db;
    public ParticipantSessionRepository(JdbcTemplate db){this.db=db;}
    public record State(String title,String pin,String nickname,String state,Instant expiresAt,String guidelines,String criteria,String antiCheat,String aiPolicy,boolean submitted) {}
    public State getState(String id,String pid) {
        db.queryForList("SELECT id FROM evaluations WHERE id=? FOR UPDATE",id);
        return db.queryForObject("SELECT e.*,p.nickname,COALESCE(s.ai_policy,'DISABLED') ai_policy,EXISTS(SELECT 1 FROM submissions x WHERE x.participant_id=p.id) submitted FROM evaluations e JOIN participants p ON p.evaluation_id=e.id LEFT JOIN evaluation_settings s ON s.evaluation_id=e.id WHERE e.id=? AND p.id=?",(r,n)->new State(r.getString("title"),r.getString("pin"),r.getString("nickname"),r.getString("state"),r.getObject("ends_at",OffsetDateTime.class)!=null?r.getObject("ends_at",OffsetDateTime.class).toInstant():r.getObject("expires_at",OffsetDateTime.class)==null?null:r.getObject("expires_at",OffsetDateTime.class).toInstant(),r.getString("evaluation_content"),r.getString("criteria_json"),r.getString("anti_cheat_json"),r.getString("ai_policy"),r.getBoolean("submitted")),id,pid);
    }
    public Optional<Instant> getAcceptance(String pid,String version){return db.query("SELECT accepted_at FROM participant_policy_acceptances WHERE participant_id=? AND policy_version=?",(r,n)->r.getObject("accepted_at",OffsetDateTime.class).toInstant(),pid,version).stream().findFirst();}
    public void saveAcceptance(String id,String pid,String version){db.update("INSERT INTO participant_policy_acceptances(participant_id,evaluation_id,policy_version,accepted_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",pid,id,version);}
}
