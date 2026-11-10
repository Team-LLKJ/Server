package kr.lit.api;

import kr.lit.auth.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.util.*;

@Repository
public class ReportArchiveRepository {
    private final JdbcTemplate db;
    public ReportArchiveRepository(JdbcTemplate db){this.db=db;}
    public record Target(String evaluationId,String participantId,String className,String nickname,boolean submitted) {}
    @Transactional
    public List<Target> getTargets(String id,boolean assessment,String classId) {
        AccountAccess.require(AuthModels.Role.TEACHER);
        if(assessment)AssessmentAccess.requireTemplate(db,id,false);
        else {AssessmentAccess.beforeEvaluation(db,id);if(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE id=?",Integer.class,id)==0)throw missing();}
        if(classId!=null&&db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE id=? AND assessment_id=?",Integer.class,classId,id)==0)throw missing();
        String filter=assessment?"e.assessment_id=?":"e.id=?";var params=new ArrayList<Object>();params.add(id);
        if(classId!=null){filter+=" AND e.id=?";params.add(classId);}
        return db.query("SELECT e.id evaluation_id,p.id participant_id,COALESCE(e.class_name,e.title) class_name,p.nickname,EXISTS(SELECT 1 FROM submissions s WHERE s.participant_id=p.id AND s.evaluation_id=e.id) submitted FROM evaluations e JOIN participants p ON p.evaluation_id=e.id WHERE "+filter+" ORDER BY e.id,p.nickname,p.id LIMIT 201",(r,n)->new Target(r.getString("evaluation_id"),r.getString("participant_id"),r.getString("class_name"),r.getString("nickname"),r.getBoolean("submitted")),params.toArray());
    }
    private ApiError.Failure missing(){return new ApiError.Failure(HttpStatus.NOT_FOUND,"EVALUATION_NOT_FOUND","접근 가능한 반을 찾을 수 없습니다.");}
}
