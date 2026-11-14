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
    private final int endGraceSeconds;
    public ParticipantAccess(JdbcTemplate db,@org.springframework.beans.factory.annotation.Value("${lit.evaluation.end-grace-seconds:0}") int endGraceSeconds) {this.db=db;this.endGraceSeconds=Math.max(0,endGraceSeconds);}
    /** 제출한 참가자는 평가 화면(문항·답안·AI 대화·AI 활용 기록 등)을 다시 볼 수 없습니다(2026-11-14). 나가기와 상태 확인만 됩니다(authenticateLobby). */
    public static final String SUBMITTED_MESSAGE="제출한 참가자는 평가 화면과 기록을 다시 볼 수 없습니다.";
    public Guest authenticate(String evaluationId,String token) {return authenticate(evaluationId,token,false,false);}
    /** 상태 확인·하트비트·나가기: 제출한 참가자도 됩니다(응답은 대기실 정보뿐이고 기록은 없습니다). */
    public Guest authenticateLobby(String evaluationId,String token) {return authenticate(evaluationId,token,true,false);}
    /** 토큰으로 다시 들어오기: 나간(비활성) 참가자는 되지만 제출한 참가자는 안 됩니다. */
    public Guest authenticateReconnect(String evaluationId,String token){return authenticate(evaluationId,token,false,true);}
    private Guest authenticate(String evaluationId,String token,boolean allowSubmitted,boolean allowInactive) {
        if(token==null||token.isBlank())throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"PARTICIPANT_TOKEN_REQUIRED","참가자 토큰이 필요합니다.");
        if(!token.matches("[a-f0-9]{64}"))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","참가자 토큰이 올바르지 않습니다.");
        var rows=db.queryForList("SELECT id,active,expelled_at,(SELECT s.submission_mode FROM submissions s WHERE s.participant_id=participants.id AND s.evaluation_id=participants.evaluation_id) submission_mode FROM participants WHERE evaluation_id=? AND ((token_hashed=TRUE AND token=?) OR (token_hashed=FALSE AND token=?))",evaluationId,AuthService.hash(token),token);
        if(rows.isEmpty())throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","참가자 토큰 또는 참여 상태를 확인해주세요.");
        var row=rows.get(0);
        if(row.get("EXPELLED_AT")!=null)throw new ApiError.Failure(HttpStatus.FORBIDDEN,"PARTICIPANT_EXPELLED","교사에 의해 참여가 종료되어 다시 접속할 수 없습니다.");
        String mode=(String)row.get("SUBMISSION_MODE");boolean submitted=mode!=null;
        if(submitted&&!allowSubmitted&&!inGrace(evaluationId,mode))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN",SUBMITTED_MESSAGE);
        if(!Boolean.TRUE.equals(row.get("ACTIVE"))&&!allowInactive&&!submitted)throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","참가자 토큰 또는 참여 상태를 확인해주세요.");
        return new Guest((String)row.get("ID"),evaluationId);
    }
    /** 종료·마감으로 자동 제출된(AUTO_*) 학생은 유예 시간(lit.evaluation.end-grace-seconds) 동안만 계속 저장할 수 있습니다. 직접 제출(MANUAL)은 바로 막습니다. */
    private boolean inGrace(String evaluationId,String mode) {
        if(endGraceSeconds<=0||!mode.startsWith("AUTO_"))return false;
        var rows=db.queryForList("SELECT ended_at,ends_at FROM evaluations WHERE id=?",evaluationId);
        if(rows.isEmpty())return false;
        Object ended=rows.get(0).get("ENDED_AT"),ends=rows.get(0).get("ENDS_AT");
        Object cutoff="AUTO_DEADLINE".equals(mode)&&ends!=null?ends:ended;
        java.time.Instant at=cutoff instanceof java.time.OffsetDateTime o?o.toInstant():cutoff instanceof java.sql.Timestamp t?t.toInstant():null;
        return at!=null&&java.time.Instant.now().isBefore(at.plusSeconds(endGraceSeconds));
    }
}
