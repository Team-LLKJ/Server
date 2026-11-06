package kr.lit.api;

import kr.lit.auth.AuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class ParticipantReconnectRepository {
    private final JdbcTemplate db;
    public ParticipantReconnectRepository(JdbcTemplate db){this.db=db;}
    public record ReconnectParticipant(String participantId,String nickname) {}
    public Optional<ReconnectParticipant> findParticipant(String evaluationId,String token) {
        return db.query("SELECT id,nickname FROM participants WHERE evaluation_id=? AND ((token_hashed=TRUE AND token=?) OR (token_hashed=FALSE AND token=?))",
                (r,n)->new ReconnectParticipant(r.getString("id"),r.getString("nickname")),evaluationId,AuthService.hash(token),token).stream().findFirst();
    }
    public void activateParticipant(String evaluationId,String participantId) {
        db.update("UPDATE participants SET active=TRUE,last_seen=CURRENT_TIMESTAMP WHERE evaluation_id=? AND id=?",evaluationId,participantId);
    }
}
