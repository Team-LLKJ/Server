package kr.lit.api;

import kr.lit.auth.AccountAccess;
import kr.lit.auth.AuthModels.Role;
import kr.lit.auth.ParticipantAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
@Transactional
public class EvaluationContentService {
    private final JdbcTemplate db;
    private final EvaluationService evaluations;
    public EvaluationContentService(JdbcTemplate db,EvaluationService evaluations) {
        this.db=db;this.evaluations=evaluations;
    }
    public record Content(String evaluationId,String evaluationContent) {}

    private Content read(String id) {
        return new Content(id,db.queryForObject("SELECT evaluation_content FROM evaluations WHERE id=?",String.class,id));
    }
    public Content teacher(String id) {
        AccountAccess.require(Role.TEACHER);
        evaluations.details(id);
        return read(id);
    }
    public Content update(String id,String value) {
        AccountAccess.require(Role.TEACHER);
        var evaluation=evaluations.details(id);
        if(!evaluation.state().equals("WAITING"))
            throw new ApiError.Failure(HttpStatus.CONFLICT,"EVALUATION_CONTENT_LOCKED","평가 시작 후에는 평가 내용을 수정할 수 없습니다.");
        if(!evaluation.expiresAt().isAfter(Instant.now()))
            throw new ApiError.Failure(HttpStatus.GONE,"PIN_EXPIRED","만료된 평가입니다.");
        db.update("UPDATE evaluations SET evaluation_content=? WHERE id=?",value,id);
        return read(id);
    }
    public Content student(String id,String token) {
        new ParticipantAccess(db).authenticate(id,token);
        return read(id);
    }
}
