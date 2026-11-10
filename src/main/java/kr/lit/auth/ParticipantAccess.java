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
    public Guest authenticate(String evaluationId,String token) {return authenticate(evaluationId,token,false,false);}
    public Guest authenticateReport(String evaluationId,String token) {return authenticate(evaluationId,token,true,false);}
    public Guest authenticateReconnect(String evaluationId,String token){return authenticate(evaluationId,token,false,true);}
    private Guest authenticate(String evaluationId,String token,boolean reportOnly,boolean allowInactive) {
        if(token==null||token.isBlank())throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"PARTICIPANT_TOKEN_REQUIRED","참가자 토큰이 필요합니다.");
        if(!token.matches("[a-f0-9]{64}"))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","참가자 토큰이 올바르지 않습니다.");
        var rows=db.queryForList("SELECT id,active,expelled_at,EXISTS (SELECT 1 FROM submissions s WHERE s.participant_id=participants.id AND s.evaluation_id=participants.evaluation_id) submitted FROM participants WHERE evaluation_id=? AND ((token_hashed=TRUE AND token=?) OR (token_hashed=FALSE AND token=?))",evaluationId,AuthService.hash(token),token);
        if(rows.isEmpty())throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","참가자 토큰 또는 참여 상태를 확인해주세요.");
        var row=rows.get(0);
        if(row.get("EXPELLED_AT")!=null)throw new ApiError.Failure(HttpStatus.FORBIDDEN,"PARTICIPANT_EXPELLED","교사에 의해 참여가 종료되어 다시 접속할 수 없습니다.");
        if(!Boolean.TRUE.equals(row.get("ACTIVE"))&&!allowInactive&&!(reportOnly&&Boolean.TRUE.equals(row.get("SUBMITTED"))))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","참가자 토큰 또는 참여 상태를 확인해주세요.");
        return new Guest((String)row.get("ID"),evaluationId);
    }
}
