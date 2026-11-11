package kr.lit.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static kr.lit.api.AssessmentModels.Indicator;

/**
 * 제출 후 채점 대기열과 ③ 루브릭 판정 저장소.
 * <ul>
 *   <li>제출하면 grading_analyses가 PENDING(제출 버전 = requested_at)이 되고, 답안 기여도 → (질문 용도는 질문할 때 이미 분류) → 루브릭 순서로 처리됩니다.</li>
 *   <li>루브릭은 답안 기여도 2단계와 질문 용도 분류가 끝난 학생만, 제출 순서대로 처리합니다(rubric_runs). 다시 제출하면 이전 판정은 버리고 마지막 제출만 채점합니다.</li>
 *   <li>학생별 gradingStatus: QUEUED / GRADING / DONE / FAILED. 실패한 루브릭은 1분 뒤 최대 3번 다시 시도하고, 그래도 실패하면 FAILED(교사가 다시 시도 가능).</li>
 * </ul>
 */
@Component
public class GradingQueue {
    public static final String QUEUED="QUEUED",GRADING="GRADING",DONE="DONE",FAILED="FAILED";
    public static final int MAX_ATTEMPTS=3;
    static final Duration RETRY_AFTER=Duration.ofMinutes(1),STALE_RUNNING=Duration.ofMinutes(15);
    public record Target(String evaluationId,String participantId,OffsetDateTime requestedAt) {}
    public record StudentStatus(String participantId,String nickname,String gradingStatus,String errorCode) {}
    public record Progress(String evaluationId,boolean evaluationEnded,boolean resultsVisible,int total,int submitted,int done,int grading,int queued,int failed,List<StudentStatus> students) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SemanticJudgeConfig config;
    private final boolean resultsAfterEnd;
    public GradingQueue(JdbcTemplate db,ObjectMapper json,SemanticJudgeConfig config,@Value("${lit.grading.results-after-end:true}") boolean resultsAfterEnd){
        this.db=db;this.json=json;this.config=config;this.resultsAfterEnd=resultsAfterEnd;
    }
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}

    // ---------- 대기열 ----------
    /** 루브릭 판정을 시작할 수 있는 학생(제출 순서). 답안 기여도·질문 용도가 끝나야 합니다. */
    public List<Target> ready(int limit) {
        var sql=new StringBuilder("SELECT g.evaluation_id,g.participant_id,g.requested_at FROM grading_analyses g LEFT JOIN rubric_runs r ON r.participant_id=g.participant_id "+
                "WHERE g.analysis_status='DONE' AND g.requested_at IS NOT NULL "+
                "AND (g.semantic_status IS NULL OR g.semantic_status IN ('DONE','DISABLED') OR (g.semantic_status IN ('PARTIAL','UNAVAILABLE') AND g.semantic_attempts>=?)) "+
                "AND (r.participant_id IS NULL OR r.requested_at<>g.requested_at OR r.status='QUEUED' "+
                "OR (r.status='FAILED' AND r.attempts<? AND r.updated_at<?) OR (r.status='GRADING' AND r.updated_at<?)) ");
        var args=new ArrayList<Object>(List.of(SemanticJudge.MAX_ATTEMPTS,MAX_ATTEMPTS,now().minus(RETRY_AFTER),now().minus(STALE_RUNNING)));
        if(config.isPurposeActive()) {
            sql.append("AND NOT EXISTS (SELECT 1 FROM ai_turns t LEFT JOIN purpose_classifications c ON c.conversation_id=t.id WHERE t.participant_id=g.participant_id "+
                    "AND t.state<>'PENDING' AND (c.conversation_id IS NULL OR (c.status='FAILED' AND c.attempts<?))) ");
            args.add(PurposeClassifier.MAX_ATTEMPTS);
        }
        sql.append("ORDER BY g.requested_at LIMIT ?");args.add(limit);
        return db.query(sql.toString(),(r,n)->new Target(r.getString(1),r.getString(2),r.getObject(3,OffsetDateTime.class)),args.toArray());
    }
    /** 이 제출 버전의 루브릭 판정을 시작합니다. 다른 작업이 먼저 가져갔으면 false. */
    public boolean start(Target t) {
        var rows=db.queryForList("SELECT status,requested_at,attempts FROM rubric_runs WHERE participant_id=?",t.participantId());
        if(rows.isEmpty()) {
            db.update("INSERT INTO rubric_runs(participant_id,evaluation_id,status,requested_at,attempts,updated_at,started_at) VALUES (?,?,'GRADING',?,0,?,?)",
                    t.participantId(),t.evaluationId(),t.requestedAt(),now(),now());
            return true;
        }
        var row=rows.get(0);boolean sameVersion=t.requestedAt().toInstant().equals(asInstant(row.get("REQUESTED_AT")));
        int attempts=sameVersion?((Number)row.get("ATTEMPTS")).intValue():0;
        return db.update("UPDATE rubric_runs SET evaluation_id=?,status='GRADING',requested_at=?,attempts=?,error_code=NULL,updated_at=?,started_at=? WHERE participant_id=? AND status=? AND requested_at=?",
                t.evaluationId(),t.requestedAt(),attempts,now(),now(),t.participantId(),row.get("STATUS"),row.get("REQUESTED_AT"))==1;
    }
    /** 판정 하나를 저장합니다. 그사이 다시 제출됐으면(제출 버전이 바뀌면) 버리고 false. */
    public boolean save(Target t,RubricJudge.Judgment judgment,String cacheKey) {
        if(!current(t)) return false;
        db.update("MERGE INTO rubric_judgments(participant_id,indicator,evaluation_id,cache_key,result_json,prompt_version,created_at) KEY(participant_id,indicator) VALUES (?,?,?,?,?,?,?)",
                t.participantId(),judgment.indicator().name(),t.evaluationId(),cacheKey,encode(judgment),judgment.promptVersion(),now());
        return true;
    }
    public Optional<RubricJudge.Judgment> cached(String participantId,Indicator indicator,String cacheKey) {
        var rows=db.queryForList("SELECT result_json FROM rubric_judgments WHERE participant_id=? AND indicator=? AND cache_key=?",String.class,participantId,indicator.name(),cacheKey);
        return rows.isEmpty()?Optional.empty():Optional.of(decode(rows.get(0)));
    }
    public void complete(Target t) {
        db.update("UPDATE rubric_runs SET status='DONE',error_code=NULL,updated_at=?,completed_at=? WHERE participant_id=? AND requested_at=? AND status='GRADING'",now(),now(),t.participantId(),t.requestedAt());
    }
    public void fail(Target t,String code) {
        db.update("UPDATE rubric_runs SET status='FAILED',attempts=attempts+1,error_code=?,updated_at=? WHERE participant_id=? AND requested_at=? AND status='GRADING'",code,now(),t.participantId(),t.requestedAt());
    }
    private boolean current(Target t) {
        var rows=db.queryForList("SELECT requested_at FROM grading_analyses WHERE participant_id=?",OffsetDateTime.class,t.participantId());
        return !rows.isEmpty()&&rows.get(0)!=null&&rows.get(0).isEqual(t.requestedAt());
    }
    /** 교사가 실패한 채점을 다시 시도합니다(시도 횟수 초기화). */
    public void retry(String participantId) {
        db.update("UPDATE rubric_runs SET status='QUEUED',attempts=0,error_code=NULL,updated_at=? WHERE participant_id=? AND status='FAILED'",now(),participantId);
        db.update("UPDATE grading_analyses SET semantic_status='PENDING',semantic_attempts=0 WHERE participant_id=? AND semantic_status IN ('PARTIAL','UNAVAILABLE')",participantId);
        db.update("UPDATE grading_analyses SET analysis_status='PENDING',requested_at=? WHERE participant_id=? AND analysis_status='FAILED'",now(),participantId);
    }

    // ---------- 저장된 판정 ----------
    /** 현재 제출 버전의 판정만 돌려줍니다(다시 제출하면 이전 판정은 쓰지 않습니다). */
    public Map<Indicator,RubricJudge.Judgment> judgments(String participantId) {
        var out=new EnumMap<Indicator,RubricJudge.Judgment>(Indicator.class);
        var version=db.queryForList("SELECT r.status FROM rubric_runs r JOIN grading_analyses g ON g.participant_id=r.participant_id AND g.requested_at=r.requested_at WHERE r.participant_id=?",String.class,participantId);
        if(version.isEmpty()) return out;
        db.query("SELECT result_json FROM rubric_judgments WHERE participant_id=?",r->{var j=decode(r.getString(1));out.put(j.indicator(),j);},participantId);
        return out;
    }
    /** 교사가 루브릭 단계를 확정한 보정 기록(AI 원래 답과 함께 보관). */
    public void label(String evaluationId,String participantId,Indicator indicator,int teacherLevel,String teacherId,RubricJudge.Judgment judgment) {
        db.update("INSERT INTO rubric_labels(evaluation_id,participant_id,indicator,teacher_level,ai_level,ai_status,votes,haiku_level,needs_review,review_reason,prompt_version,result_json,teacher_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                evaluationId,participantId,indicator.name(),teacherLevel,judgment==null?null:judgment.level(),judgment==null?null:judgment.status(),
                judgment==null?null:encode(judgment.votes()),judgment==null?null:Objects.toString(judgment.haikuLevel(),null),judgment!=null&&judgment.needsReview(),
                judgment==null?null:judgment.reviewReason(),judgment==null?RubricJudge.PROMPT_VERSION:judgment.promptVersion(),judgment==null?null:encode(judgment),teacherId,now());
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
        if(!config.isRubricActive()) return DONE;
        var r=db.queryForList("SELECT status,attempts,requested_at FROM rubric_runs WHERE participant_id=?",participantId);
        if(r.isEmpty()) return GRADING;
        var run=r.get(0);
        if(!Objects.equals(asInstant(run.get("REQUESTED_AT")),asInstant(row.get("REQUESTED_AT")))) return GRADING;
        String status=(String)run.get("STATUS");
        if(DONE.equals(status)) return DONE;
        if(FAILED.equals(status)&&((Number)run.get("ATTEMPTS")).intValue()>=MAX_ATTEMPTS) return FAILED;
        return GRADING;
    }
    private static java.time.Instant asInstant(Object v){return v instanceof java.sql.Timestamp t?t.toInstant():v instanceof OffsetDateTime o?o.toInstant():null;}
    public Progress progress(String evaluationId) {
        var students=new ArrayList<StudentStatus>();
        db.query("SELECT p.id,p.nickname,(SELECT error_code FROM rubric_runs r WHERE r.participant_id=p.id) err FROM participants p WHERE p.evaluation_id=? ORDER BY p.nickname",
                r->{students.add(new StudentStatus(r.getString(1),r.getString(2),status(r.getString(1)),r.getString(3)));},evaluationId);
        int submitted=(int)students.stream().filter(s->s.gradingStatus()!=null).count();
        return new Progress(evaluationId,ended(evaluationId),resultsVisible(evaluationId),students.size(),submitted,
                count(students,DONE),count(students,GRADING),count(students,QUEUED),count(students,FAILED),students);
    }
    private static int count(List<StudentStatus> list,String status){return (int)list.stream().filter(s->status.equals(s.gradingStatus())).count();}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private RubricJudge.Judgment decode(String v){try{return json.readValue(v,new TypeReference<RubricJudge.Judgment>(){});}catch(Exception e){throw new IllegalStateException(e);}}
}
