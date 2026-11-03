package kr.lit.auth;

import kr.lit.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import static kr.lit.auth.AuthModels.*;

/** 공유 권한의 단일 검사 지점. 교사 작업은 틀 → 반 순서로 잠가 공유 해제와 직렬화합니다. */
public final class AssessmentAccess {
    private AssessmentAccess() {}
    public record Access(String ownerId,String role) {}
    public static Access requireTemplate(JdbcTemplate db,String assessmentId,boolean ownerOnly) {
        return requireTemplate(db,assessmentId,ownerOnly,"ASSESSMENT_NOT_FOUND");
    }
    private static Access requireTemplate(JdbcTemplate db,String id,boolean ownerOnly,String notFoundCode) {
        String actor=AccountAccess.require(Role.TEACHER).userId();
        var owners=db.queryForList("SELECT teacher_id FROM assessment_templates WHERE id=? FOR UPDATE",String.class,id);
        if(owners.isEmpty())throw missing(notFoundCode);
        String owner=owners.get(0);
        if(owner.equals(actor))return new Access(owner,"OWNER");
        boolean editor=db.queryForObject("SELECT COUNT(*) FROM assessment_collaborators c JOIN app_users u ON u.id=c.teacher_id WHERE c.assessment_id=? AND c.teacher_id=? AND u.user_role='TEACHER' AND u.user_status='ACTIVE'",Integer.class,id,actor)>0;
        if(!editor)throw missing(notFoundCode);
        if(ownerOnly)throw new ApiError.Failure(HttpStatus.FORBIDDEN,"ASSESSMENT_OWNER_REQUIRED","수행평가를 만든 교사만 수행할 수 있습니다.");
        return new Access(owner,"EDITOR");
    }
    /** 공개 PIN·학생·백그라운드 흐름의 기존 권한은 유지하며, 교사인 경우 반 소속 틀 권한을 검사합니다. */
    public static void beforeEvaluation(JdbcTemplate db,String evaluationId) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof Principal p)||p.role()!=Role.TEACHER)return;
        var rows=db.query("SELECT teacher_id,assessment_id FROM evaluations WHERE id=?",(r,n)->new String[]{r.getString("teacher_id"),r.getString("assessment_id")},evaluationId);
        if(rows.isEmpty())throw missing("EVALUATION_NOT_FOUND");
        String[] row=rows.get(0);
        if(row[1]!=null)requireTemplate(db,row[1],false,"EVALUATION_NOT_FOUND");
        else if(!p.userId().equals(row[0]))throw missing("EVALUATION_NOT_FOUND");
    }
    private static ApiError.Failure missing(String code) {return new ApiError.Failure(HttpStatus.NOT_FOUND,code,"접근 가능한 수행평가를 찾을 수 없습니다.");}
}
