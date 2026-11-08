package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

@Repository
public class ClassEventRepository {
    private final JdbcTemplate db;
    public ClassEventRepository(JdbcTemplate db){this.db=db;}
    public record Item(long eventId,String participantId,String nickname,String type,Instant occurredAt) {}
    public List<Item> getEvents(String id,long after){return db.query("SELECT e.id,e.participant_id,p.nickname,e.event_type,e.created_at FROM learning_events e JOIN participants p ON p.id=e.participant_id WHERE e.evaluation_id=? AND e.id>? AND (e.event_type IN ('SUBMITTED','PARTICIPANT_EXPELLED') OR e.event_type IN ("+WorkModels.ClientEventType.warningSql()+")) ORDER BY e.id LIMIT 200",(r,n)->new Item(r.getLong("id"),r.getString("participant_id"),r.getString("nickname"),r.getString("event_type"),r.getObject("created_at",OffsetDateTime.class).toInstant()),id,after);}
}
