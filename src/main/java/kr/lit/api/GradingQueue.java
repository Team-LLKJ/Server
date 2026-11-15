package kr.lit.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 제출 후 채점 진행 상태와 결과 공개 시점.
 * <ul>
 *   <li>제출하면 답안 기여도(grading_analyses) → (질문 용도는 질문할 때 이미 분류) → 루브릭(과정 채점 작업, process_grading_jobs) 순서로 처리됩니다.</li>
 *   <li>학생별 gradingStatus: QUEUED / GRADING / DONE / FAILED. 다시 제출하면 마지막 제출본만 채점합니다.</li>
 *   <li>평가가 끝나기 전(교사 종료·시간 만료 전)에는 채점 결과를 내려 주지 않습니다.</li>
 * </ul>
 */
@Component
public class GradingQueue {
    public static final String QUEUED="QUEUED",GRADING="GRADING",DONE="DONE",FAILED="FAILED";
    public record StudentStatus(String participantId,String nickname,String gradingStatus,String errorCode) {}
    public record Progress(String evaluationId,boolean evaluationEnded,boolean resultsVisible,int total,int submitted,int done,int grading,int queued,int failed,List<StudentStatus> students) {}

    private final JdbcTemplate db;
    private final SemanticJudgeConfig config;
    private final boolean resultsAfterEnd;
    private final ProcessGradingRepository process;
    public GradingQueue(JdbcTemplate db,SemanticJudgeConfig config,ProcessGradingRepository process,@Value("${lit.grading.results-after-end:true}") boolean resultsAfterEnd){
        this.db=db;this.config=config;this.process=process;this.resultsAfterEnd=resultsAfterEnd;
    }
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}

    /** 교사가 실패한 채점을 다시 시도합니다: 답안 기여도·분석 실패는 다시 대기, 과정 채점(루브릭)은 새 작업을 만듭니다. */
    public void retry(String evaluationId,String participantId) {
        db.update("UPDATE grading_analyses SET semantic_status='PENDING',semantic_attempts=0 WHERE participant_id=? AND semantic_status IN ('PARTIAL','UNAVAILABLE')",participantId);
        db.update("UPDATE grading_analyses SET analysis_status='PENDING',requested_at=? WHERE participant_id=? AND analysis_status='FAILED'",now(),participantId);
        var latest=db.queryForList("SELECT status FROM process_grading_jobs WHERE participant_id=? ORDER BY version DESC LIMIT 1",String.class,participantId);
        if(!latest.isEmpty()&&"FAILED".equals(latest.get(0))) process.queueSubmission(evaluationId,participantId);
    }

    // ---------- 상태·공개 ----------
    /** 교사가 평가 종료를 누르거나 평가 시간이 끝났는지. */
    public boolean ended(String evaluationId) {
        return db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE id=? AND (state='ENDED' OR (state='STARTED' AND COALESCE(ends_at,expires_at)<=CURRENT_TIMESTAMP))",
                Integer.class,evaluationId)>0;
    }
    /** 평가가 끝나기 전에는 채점 결과를 내려 주지 않습니다(lit.grading.results-after-end, 기본 true). */
    public boolean resultsVisible(String evaluationId){return !resultsAfterEnd||ended(evaluationId);}
    public String status(String participantId) {
        if(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,participantId)==0) return null;
        var g=db.queryForList("SELECT analysis_status,semantic_status,semantic_attempts,requested_at FROM grading_analyses WHERE participant_id=?",participantId);
        if(g.isEmpty()) return QUEUED;
        var row=g.get(0);String analysis=(String)row.get("ANALYSIS_STATUS");
        if("FAILED".equals(analysis)) return FAILED;
        if(!"DONE".equals(analysis)) return QUEUED;
        String semantic=(String)row.get("SEMANTIC_STATUS");int semanticAttempts=row.get("SEMANTIC_ATTEMPTS")==null?0:((Number)row.get("SEMANTIC_ATTEMPTS")).intValue();
        boolean semanticSettled=semantic==null||List.of("DONE","DISABLED").contains(semantic)||(List.of("PARTIAL","UNAVAILABLE").contains(semantic)&&semanticAttempts>=SemanticJudge.MAX_ATTEMPTS);
        boolean purposesSettled=!config.isPurposeActive()||db.queryForObject("SELECT COUNT(*) FROM ai_turns t LEFT JOIN purpose_classifications c ON c.conversation_id=t.id "+
                "WHERE t.participant_id=? AND t.state<>'PENDING' AND (c.conversation_id IS NULL OR (c.status='FAILED' AND c.attempts<?))",Integer.class,participantId,PurposeClassifier.MAX_ATTEMPTS)==0;
        if(!semanticSettled||!purposesSettled) return GRADING;
        // 루브릭(과정 채점) — 마지막 제출본의 작업 상태
        var job=db.queryForList("SELECT status FROM process_grading_jobs WHERE participant_id=? ORDER BY version DESC LIMIT 1",String.class,participantId);
        if(job.isEmpty()) return DONE;
        return switch(job.get(0)){case "PENDING","RUNNING"->GRADING;case "FAILED"->FAILED;default->DONE;};
    }
    public Progress progress(String evaluationId) {
        var students=new ArrayList<StudentStatus>();
        db.query("SELECT p.id,p.nickname,(SELECT error_code FROM process_grading_jobs j WHERE j.participant_id=p.id ORDER BY j.version DESC LIMIT 1) err FROM participants p WHERE p.evaluation_id=? ORDER BY p.nickname",
                r->{students.add(new StudentStatus(r.getString(1),r.getString(2),status(r.getString(1)),r.getString(3)));},evaluationId);
        int submitted=(int)students.stream().filter(s->s.gradingStatus()!=null).count();
        return new Progress(evaluationId,ended(evaluationId),resultsVisible(evaluationId),students.size(),submitted,
                count(students,DONE),count(students,GRADING),count(students,QUEUED),count(students,FAILED),students);
    }
    private static int count(List<StudentStatus> list,String status){return (int)list.stream().filter(s->status.equals(s.gradingStatus())).count();}
}
