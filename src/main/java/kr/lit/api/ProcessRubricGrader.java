package kr.lit.api;

import kr.lit.db.DatabaseUpsert;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static kr.lit.api.AssessmentModels.Indicator;
import static kr.lit.api.ProcessGradingModels.*;

/**
 * ③ 루브릭 LLM 판정을 과정 채점 작업(process_grading_jobs)에 붙입니다. rubric 키가 설정되면 ProcessGradingWorker가 기존
 * 문항별 Gemini 추천 대신 이 판정으로 결과를 만듭니다.
 * <ul>
 *   <li>학생 단위: 항목마다 한 칸(Cell.questionId=null). 문항 가중치는 쓰지 않습니다.</li>
 *   <li>입력은 제출 시점에 고정된 Input.sources. 질문 용도(②)는 판정할 때의 분류 결과를 참고로 붙입니다.</li>
 *   <li>근거 인용은 원문 위치(Citation.location)로 연결합니다. 원문에서 찾지 못한 인용은 버립니다.</li>
 *   <li>같은 기록·프롬프트·모델이면 rubric_judgments에 저장된 판정을 다시 씁니다(재분석·재제출해도 같은 입력은 호출하지 않음).</li>
 *   <li>점수는 NA 항목 배점을 재분배해 100점 만점으로 계산합니다(ProcessGradingEngine.calculateStudent).</li>
 * </ul>
 */
@Component
public class ProcessRubricGrader {
    public static final String MODEL_LABEL="LLM_RUBRIC_GEMINI_X3_HAIKU";
    /** 질문 용도·답안 기여도 분류를 기다리는 최대 시간. 넘으면 있는 결과로 판정합니다. */
    static final java.time.Duration MAX_WAIT_FOR_OTHER_STAGES=java.time.Duration.ofMinutes(10);
    private final RubricJudge judge;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ProcessGradingEngine engine;
    private final GradingEvidenceValidator validator=new GradingEvidenceValidator();
    private final JudgeUsage usage;
    private final SemanticJudgeConfig config;
    public ProcessRubricGrader(RubricJudge judge,JdbcTemplate db,ObjectMapper json,ProcessGradingEngine engine,JudgeUsage usage,SemanticJudgeConfig config){
        this.judge=judge;this.db=db;this.json=json;this.engine=engine;this.usage=usage;this.config=config;
    }
    public boolean isActive(){return judge.isActive();}

    /** ①② 결과가 끝났는지(질문 용도 분류·답안 기여도 2단계). 작업이 만들어진 뒤 10분이 지나면 기다리지 않습니다. */
    public boolean ready(String participantId,Instant jobCreatedAt) {
        if(jobCreatedAt!=null&&jobCreatedAt.plus(MAX_WAIT_FOR_OTHER_STAGES).isBefore(Instant.now())) return true;
        if(config.isPurposeActive()&&db.queryForObject("SELECT COUNT(*) FROM ai_turns t LEFT JOIN purpose_classifications c ON c.conversation_id=t.id "+
                "WHERE t.participant_id=? AND t.state<>'PENDING' AND (c.conversation_id IS NULL OR (c.status='FAILED' AND c.attempts<?))",Integer.class,participantId,PurposeClassifier.MAX_ATTEMPTS)>0) return false;
        if(config.isActive()) {
            var g=db.queryForList("SELECT analysis_status,semantic_status,semantic_attempts FROM grading_analyses WHERE participant_id=?",participantId);
            if(!g.isEmpty()) {
                var row=g.get(0);String semantic=(String)row.get("SEMANTIC_STATUS");int attempts=row.get("SEMANTIC_ATTEMPTS")==null?0:((Number)row.get("SEMANTIC_ATTEMPTS")).intValue();
                if("PENDING".equals(row.get("ANALYSIS_STATUS"))||"PENDING".equals(semantic)) return false;
                if(List.of("PARTIAL","UNAVAILABLE").contains(semantic)&&attempts<SemanticJudge.MAX_ATTEMPTS) return false;
            }
        }
        return true;
    }

    /** 학생 한 명의 다섯 항목을 판정해 학생 단위 결과를 만듭니다. Gemini HTTP 실패는 JudgeFailure로 던집니다(작업 FAILED). */
    public Result grade(Input input,Runnable heartbeat) {
        try(var ignored=usage.open(input.evaluationId(),input.participantId(),JudgeUsage.Task.RUBRIC)) {
            var record=judge.buildRecord(input,purposes(input.participantId()));
            var excluded=input.policy()==null?List.<Criterion>of():input.policy().excludedCriteria();
            var cells=new ArrayList<Cell>();int confirmed=0,review=0,na=0;
            for(var criterion:Criterion.values()) {
                if(excluded.contains(criterion)) { cells.add(new Cell(null,criterion,null,null,"NOT_APPLICABLE","교사의 사전 정책에 따라 평가 대상이 아닙니다.","",List.of(),List.of(),List.of()));na++;continue; }
                heartbeat.run();
                var indicator=Indicator.valueOf(criterion.name());
                String key=judge.cacheKey(record,indicator);
                var judgment=cached(input.participantId(),indicator,key).orElseGet(()->{
                    var j=judge.judge(record,indicator,criterion.maxScore());
                    if(!j.retryable()) save(input,j,key); // Haiku가 일시적으로 없었던 결과는 저장하지 않아 다음 분석 때 다시 묻습니다.
                    return j;});
                var cell=cell(input,criterion,judgment);cells.add(cell);
                if(cell.status().equals("NOT_APPLICABLE"))na++;else if(cell.level()!=null)confirmed++;else review++;
            }
            var limitations=new ArrayList<>(input.limitations());
            limitations.add("루브릭 단계는 학생 기록 전체로 항목마다 판정했습니다(Gemini 3번, 엇갈리면 Haiku). 문항 가중치는 쓰지 않습니다.");
            if(na>0) limitations.add("해당 없음(NA) 항목의 배점은 나머지 항목에 비례해 나눠 100점 만점으로 계산했습니다.");
            if(review>0) limitations.add("판정이 엇갈리거나 기록이 부족한 항목 "+review+"개는 교사의 직접 입력이 필요합니다.");
            String summary="AI 추천 확정 "+confirmed+"개, 교사 확인 필요 "+review+"개, 해당 없음 "+na+"개.";
            var compliance=new Compliance("INSUFFICIENT_EVIDENCE","규정 준수 여부는 교사가 판단합니다(루브릭 AI 판정 대상이 아닙니다).",List.of(),List.of());
            return engine.studentResult(input,cells,compliance,List.copyOf(limitations),summary);
        }
    }
    private Cell cell(Input input,Criterion criterion,RubricJudge.Judgment j) {
        var info=new JudgeInfo(j.votes(),j.haikuLevel(),j.needsReview(),j.reviewReason(),j.method(),j.runs(),j.promptVersion(),j.geminiModel(),j.haikuModel(),j.haikuError());
        var citations=citations(input,j.evidence());
        var limitations=new ArrayList<String>();
        if(RubricJudge.STATUS_NA.equals(j.status()))
            return new Cell(null,criterion,null,null,"NOT_APPLICABLE",RubricJudge.METHOD_SERVER.equals(j.method())?"AI 대화 기록이 없어 이 항목은 해당 없음입니다. 배점은 나머지 항목에 나눠 계산합니다."
                    :"AI 판정에서 이 항목은 해당 없음으로 나왔습니다. 배점은 나머지 항목에 나눠 계산합니다.","",List.of(),List.of(),List.of(),null,info);
        if(j.level()!=null&&!j.needsReview()) {
            var details=new Details(List.of(new BehaviorReason("충족한 핵심 조건",blankToDefault(j.met(),"근거 문장을 확인하세요."),citations)),List.of(),List.of(),
                    j.blocked()==null||j.blocked().isBlank()?List.of():List.of(j.blocked()),List.of());
            return new Cell(null,criterion,j.level(),RubricJudge.score(criterion.maxScore(),j.level()),"RECOMMENDED",blankToDefault(j.met(),""),Objects.toString(j.blocked(),""),
                    citations.isEmpty()?List.of():List.of(new Condition(criterion.name()+"_L"+j.level(),citations)),List.of(),List.copyOf(limitations),details,info);
        }
        String reason=RubricJudge.STATUS_HOLD.equals(j.status())?"최종 답안이나 활용 설명이 저장 오류로 남아 있지 않아 판단을 보류했습니다."
                :RubricJudge.REASON_INSUFFICIENT.equals(j.reviewReason())?"판단할 대화가 너무 적어 교사의 직접 입력이 필요합니다."
                :"AI 판정이 엇갈려 교사의 직접 입력이 필요합니다. 회차별 단계와 근거를 참고하세요.";
        limitations.add("교사 확인이 필요합니다.");
        return new Cell(null,criterion,null,null,"HOLD",reason,"",List.of(),List.of(),List.copyOf(limitations),
                new Details(List.of(),List.of(),List.of(reason),List.of(),List.of()),info);
    }
    /** 원문 위치로 연결합니다. 경계가 맞지 않는 인용(서로게이트 중간 등)은 버립니다. */
    private List<Citation> citations(Input input,List<RubricJudge.Evidence> evidence) {
        var out=new ArrayList<Citation>();
        for(var e:evidence) {
            try { out.addAll(validator.resolveCitations(input,GLOBAL,List.of(new Citation(e.sourceId(),e.quote(),e.start(),e.end(),null)))); }
            catch(IllegalArgumentException ignored) {}
        }
        return List.copyOf(out);
    }
    private static String blankToDefault(String v,String d){return v==null||v.isBlank()?d:v;}
    /** 대화별 질문 용도: 교사 확정 > LLM 결과. */
    private Map<Long,String> purposes(String participantId) {
        var out=new HashMap<Long,String>();
        db.query("SELECT conversation_id,purpose FROM purpose_classifications WHERE participant_id=? AND status='DONE'",r->{out.put(r.getLong(1),r.getString(2));},participantId);
        db.query("SELECT conversation_id,teacher_purpose FROM purpose_labels WHERE participant_id=? ORDER BY id",r->{out.put(r.getLong(1),r.getString(2));},participantId);
        return out;
    }
    private Optional<RubricJudge.Judgment> cached(String participantId,Indicator indicator,String key) {
        var rows=db.queryForList("SELECT result_json FROM rubric_judgments WHERE participant_id=? AND indicator=? AND cache_key=?",String.class,participantId,indicator.name(),key);
        if(rows.isEmpty()) return Optional.empty();
        try { var j=json.readValue(rows.get(0),RubricJudge.Judgment.class);return j.retryable()?Optional.empty():Optional.of(j); } catch(Exception ex) { return Optional.empty(); }
    }
    private void save(Input input,RubricJudge.Judgment judgment,String key) {
        try {
            DatabaseUpsert.save(db,"rubric_judgments","participant_id,indicator,evaluation_id,cache_key,result_json,prompt_version,created_at","participant_id,indicator","?,?,?,?,?,?,?",
                    input.participantId(),judgment.indicator().name(),input.evaluationId(),key,json.writeValueAsString(judgment),judgment.promptVersion(),OffsetDateTime.now(ZoneOffset.UTC));
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
}
