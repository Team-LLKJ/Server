package kr.lit.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** 현재 채점 자료를 읽기만 합니다. 보고서에 저장할 점수·근거 선택은 서비스에서 수행합니다. */
@Repository
public class ReportGradingRepository {
    private final JdbcTemplate db;private final ObjectMapper json;
    public ReportGradingRepository(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public record TeacherRow(int criterionId,int level,String feedback) {}
    public record Source(List<AssessmentModels.Criterion> criteria,List<TeacherRow> teachers,AssessmentModels.Analysis analysis,String status,boolean configured) {}
    public Source getSource(String id,String pid) {
        db.queryForList("SELECT id FROM participants WHERE id=? AND evaluation_id=? FOR UPDATE",pid,id);
        var rubric=db.queryForList("SELECT criteria_json FROM evaluation_rubrics WHERE evaluation_id=?",String.class,id);
        List<AssessmentModels.Criterion> criteria=null;AssessmentModels.Analysis analysis=null;
        var data=db.queryForList("SELECT result_json FROM grading_analyses WHERE participant_id=? AND evaluation_id=? AND analysis_status='DONE'",String.class,pid,id);
        try {
            if(!rubric.isEmpty())criteria=json.readValue(rubric.get(0),new TypeReference<List<AssessmentModels.Criterion>>(){});
            if(!data.isEmpty()&&data.get(0)!=null)analysis=json.readValue(data.get(0),AssessmentModels.Analysis.class);
        }catch(Exception ex){throw new IllegalStateException("저장된 채점 자료를 읽을 수 없습니다.",ex);}
        var teachers=db.query("SELECT criterion_id,score_level,feedback FROM grading_scores WHERE participant_id=? AND evaluation_id=? ORDER BY criterion_id",(r,n)->new TeacherRow(r.getInt("criterion_id"),r.getInt("score_level"),r.getString("feedback")),pid,id);
        var statuses=db.queryForList("SELECT grading_status FROM grading_results WHERE participant_id=? AND evaluation_id=?",String.class,pid,id);
        return new Source(criteria,teachers,analysis,statuses.isEmpty()?"NOT_STARTED":statuses.get(0),!rubric.isEmpty()||!teachers.isEmpty()||analysis!=null);
    }
}
