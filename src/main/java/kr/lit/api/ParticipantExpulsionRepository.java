package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

@Repository
public class ParticipantExpulsionRepository {
    private final JdbcTemplate db;
    public ParticipantExpulsionRepository(JdbcTemplate db){this.db=db;}
    public record Participant(String id,Instant expelledAt) {}
    public record PendingTurn(long id,String questionId,String requestId) {}
    public Optional<Participant> getParticipant(String id,String pid){return db.query("SELECT id,expelled_at FROM participants WHERE evaluation_id=? AND id=?",(r,n)->{var at=r.getObject("expelled_at",OffsetDateTime.class);return new Participant(r.getString("id"),at==null?null:at.toInstant());},id,pid).stream().findFirst();}
    public void expelParticipant(String id,String pid){db.update("UPDATE participants SET active=FALSE,expelled_at=CURRENT_TIMESTAMP WHERE evaluation_id=? AND id=? AND expelled_at IS NULL",id,pid);}
    public List<PendingTurn> getPendingTurns(String id,String pid){return db.query("SELECT id,question_id,request_id FROM ai_turns WHERE evaluation_id=? AND participant_id=? AND state='PENDING' ORDER BY id",(r,n)->new PendingTurn(r.getLong("id"),r.getString("question_id"),r.getString("request_id")),id,pid);}
    public void failTurn(long id){db.update("UPDATE ai_turns SET state='FAILED',error_code='PARTICIPANT_EXPELLED',completed_at=CURRENT_TIMESTAMP WHERE id=? AND state='PENDING'",id);}
    public void saveEvent(String id,String pid,String qid,String type,String details){db.update("INSERT INTO learning_events(evaluation_id,participant_id,question_id,event_type,details,created_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",id,pid,qid,type,details);}
}
