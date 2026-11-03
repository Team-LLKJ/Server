package kr.lit.auth;

import kr.lit.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Guest participation credentials are separate from teacher account credentials. */
@Component
public class ParticipantAccess {
    public record Guest(String participantId,String evaluationId) {}
    private final JdbcTemplate db;
    public ParticipantAccess(JdbcTemplate db) {this.db=db;}
    public Guest authenticate(String evaluationId,String token) {
        if(token==null||token.isBlank())throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"PARTICIPANT_TOKEN_REQUIRED","참가자 토큰이 필요합니다.");
        if(!token.matches("[a-f0-9]{64}"))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","참가자 토큰이 올바르지 않습니다.");
        var rows=db.query("SELECT id FROM participants WHERE evaluation_id=? AND active=TRUE AND ((token_hashed=TRUE AND token=?) OR (token_hashed=FALSE AND token=?))",
                (r,n)->new Guest(r.getString("id"),evaluationId),evaluationId,AuthService.hash(token),token);
        if(rows.isEmpty())throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","참가자 토큰 또는 참여 상태를 확인해주세요.");
        return rows.get(0);
    }
}
