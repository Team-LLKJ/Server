package kr.lit.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.lit.auth.AccountAccess;
import kr.lit.auth.AuthModels.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static kr.lit.api.AssessmentModels.*;

/** 교사 채점 화면 API: 제출 현황, 답안(문단 출처), AI 대화, AI 활용 분석, 루브릭, 기준별 점수와 확정. */
@Service
@Transactional
public class AssessmentService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AssessmentService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final EvaluationService evaluations;
    private final ReportService reports;
    private final AssessmentAnalyzer analyzer;
    private final NotificationService notifications;
    private final SemanticJudge judge;

    public AssessmentService(JdbcTemplate db,ObjectMapper json,EvaluationService evaluations,ReportService reports,AssessmentAnalyzer analyzer,NotificationService notifications,SemanticJudge judge) {
        this.db=db;this.json=json;this.evaluations=evaluations;this.reports=reports;this.analyzer=analyzer;this.notifications=notifications;this.judge=judge;
    }

    // ===== 공통 =====
    private ApiError.Failure fail(HttpStatus status,String code,String message) { return new ApiError.Failure(status,code,message); }
    private String teacher(String id) { var p=AccountAccess.require(Role.TEACHER);evaluations.details(id);return p.userId(); }
    private void person(String id,String pid) {
        if(db.queryForObject("SELECT COUNT(*) FROM participants WHERE evaluation_id=? AND id=?",Integer.class,id,pid)==0)
            throw fail(HttpStatus.NOT_FOUND,"PARTICIPANT_NOT_FOUND","해당 평가의 참가자가 아닙니다.");
    }
    private boolean submitted(String pid) { return db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,pid)>0; }
    private void requireSubmitted(String pid) {
        if(!submitted(pid)) throw fail(HttpStatus.CONFLICT,"SUBMISSION_REQUIRED","학생 제출(자동 제출 포함) 후 채점할 수 있습니다.");
    }
    private static Instant instant(ResultSet r,String column) throws SQLException {
        var v=r.getObject(column,OffsetDateTime.class);return v==null?null:v.toInstant();
    }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private String encode(Object v) { try { return json.writeValueAsString(v); } catch(Exception e) { throw new IllegalStateException(e); } }
    private <T> T decode(String v,TypeReference<T> type) { try { return json.readValue(v,type); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static double score(int max,int level) { return Math.round(max*level/4.0*100)/100.0; }

    // ===== 루브릭 =====
    public static List<Criterion> defaultCriteria() {
        return List.of(
            new Criterion(1,"AI 활용 목적의 적절성",15,"AI를 답 생성이 아니라 학습·탐색·이해를 위해 썼는가",List.of(
                "AI를 주로 답안 생성·대리 작성에 사용함","학습 목적 질문이 일부 있으나 답 생성 요청이 많음","학습·탐색 목적과 답 생성 요청이 섞여 있음",
                "대부분 개념 이해·자료 탐색·아이디어 구체화에 사용함","모든 질문이 학습·탐색·이해 목적에 적절함"),Indicator.PURPOSE,"프로젝트 제안 초안·교사 확인 필요"),
            new Criterion(2,"자기 주도적 사고",25,"자기 생각과 판단을 먼저 내고 AI는 보조로 썼는가",List.of(
                "자기 생각 없이 AI에 의존함","AI 질문 전 자기 생각이 거의 없음","일부 문항에서 자기 생각을 먼저 제시함",
                "대부분 자기 생각을 먼저 쓰고 질문을 구체화함","자기 주장·질문 구체화·선택 이유가 모두 분명함"),Indicator.SELF_DIRECTION,"프로젝트 제안 초안·교사 확인 필요"),
            new Criterion(3,"AI 답변의 비판적 검토",20,"AI 답을 그대로 받지 않고 검증·수정했는가",List.of(
                "AI 답변을 검토 없이 그대로 수용함","검토 흔적이 거의 없음","일부 답변을 확인하거나 수정함",
                "대부분의 답변을 확인·반박·수정함","모든 답변을 근거와 함께 검증·수정함"),Indicator.CRITICAL_REVIEW,"프로젝트 제안 초안·교사 확인 필요"),
            new Criterion(4,"최종 답안에 대한 학생의 기여",25,"자신의 해석과 근거가 최종 답안에 반영됐는가",List.of(
                "최종 답안이 AI 문장 위주임","학생 기여가 적음","학생 작성과 AI 참고가 비슷함",
                "대부분 학생의 해석과 근거로 작성함","학생 고유의 해석·근거·사례로 완성함"),Indicator.CONTRIBUTION,"프로젝트 제안 초안·교사 확인 필요"),
            new Criterion(5,"AI 활용 과정의 투명성",15,"AI 사용 범위와 반영 과정을 명확히 기록했는가",List.of(
                "AI 사용 기록이 없음","기록이 매우 부족함","도구·질문·활용 부분 중 일부를 기록함",
                "대부분 기록함","사용 도구·질문 기록·활용 부분을 모두 명확히 기록함"),Indicator.TRANSPARENCY,"프로젝트 제안 초안·교사 확인 필요"));
    }
    private List<Criterion> criteria(String id) {
        var rows=db.queryForList("SELECT criteria_json FROM evaluation_rubrics WHERE evaluation_id=?",String.class,id);
        return rows.isEmpty()?defaultCriteria():decode(rows.get(0),new TypeReference<List<Criterion>>(){});
    }
    private boolean rubricLocked(String id) { return db.queryForObject("SELECT (SELECT COUNT(*) FROM grading_scores WHERE evaluation_id=?)+(SELECT COUNT(*) FROM grading_score_changes WHERE evaluation_id=?)",Integer.class,id,id)>0; }
    private Rubric rubricView(String id) {
        var list=criteria(id);
        boolean custom=db.queryForObject("SELECT COUNT(*) FROM evaluation_rubrics WHERE evaluation_id=?",Integer.class,id)>0;
        return new Rubric(id,list,list.stream().mapToInt(Criterion::maxScore).sum(),custom,rubricLocked(id),LEVELS,AssessmentAnalyzer.MODEL,!custom);
    }
    public Rubric rubric(String id) { teacher(id);return rubricView(id); }
    /** 평가마다 기준·배점을 바꿀 수 있습니다. 합계는 100점이어야 하며, 한 명이라도 교사 점수가 저장되면 잠깁니다. */
    public Rubric saveRubric(String id,RubricInput input) {
        teacher(id);ClassSnapshots.requireStandalone(db,id);
        if(rubricLocked(id)) throw fail(HttpStatus.CONFLICT,"RUBRIC_LOCKED","이미 채점이 시작된 평가는 기준을 바꿀 수 없습니다.");
        int total=input.criteria().stream().mapToInt(CriterionInput::maxScore).sum();
        if(total!=100) throw fail(HttpStatus.BAD_REQUEST,"INVALID_RUBRIC","배점 합계는 100점이어야 합니다. (현재 "+total+"점)");
        var list=new ArrayList<Criterion>();int n=1;
        for(var c:input.criteria()) {
            var levels=c.levelDescriptions()==null?List.of("","","","",""):c.levelDescriptions();
            list.add(new Criterion(n++,c.name().strip(),c.maxScore(),c.description()==null?"":c.description().strip(),levels,c.indicator(),"교사 지정"));
        }
        db.update("MERGE INTO evaluation_rubrics(evaluation_id,criteria_json,updated_at) KEY(evaluation_id) VALUES (?,?,?)",id,encode(list),now());
        db.update("UPDATE evaluations SET criteria_json=?,criteria_kind='RUBRIC' WHERE id=? AND criteria_kind<>'TITLES'",encode(input.criteria()),id);
        return rubricView(id);
    }
    public Rubric resetRubric(String id) {
        teacher(id);ClassSnapshots.requireStandalone(db,id);
        if(rubricLocked(id)) throw fail(HttpStatus.CONFLICT,"RUBRIC_LOCKED","이미 채점이 시작된 평가는 기준을 바꿀 수 없습니다.");
        db.update("DELETE FROM evaluation_rubrics WHERE evaluation_id=?",id);
        db.update("UPDATE evaluations SET criteria_json='[]',criteria_kind='LEGACY' WHERE id=? AND criteria_kind<>'TITLES'",id);
        return rubricView(id);
    }

    // ===== 분석 =====
    private AssessmentAnalyzer.DisclosureInput disclosure(String pid) {
        var rows=db.query("SELECT tools,used_parts,reason FROM ai_disclosures WHERE participant_id=?",
                (r,n)->new AssessmentAnalyzer.DisclosureInput(r.getString("tools"),r.getString("used_parts"),r.getString("reason")),pid);
        return rows.isEmpty()?AssessmentAnalyzer.DisclosureInput.EMPTY:rows.get(0);
    }
    private Analysis compute(String id,String pid,JsonNode source) { return compute(id,pid,source,null); }
    private Analysis compute(String id,String pid,JsonNode source,String semanticFailure) { return compute(id,pid,source,semanticFailure,true); }
    /** 교사가 고친 문장 판정. 같은 문장을 여러 번 고치면 마지막 판정을 씁니다. */
    private Map<String,String> teacherLabels(String pid) {
        var labels=new HashMap<String,String>();
        db.query("SELECT question_id,sentence_text,teacher_label FROM sentence_labels WHERE participant_id=? ORDER BY id",
                r->{labels.put(AssessmentAnalyzer.sentenceKey(r.getString("question_id"),r.getString("sentence_text")),r.getString("teacher_label"));},pid);
        return labels;
    }
    /**
     * 1단계(글자 비교) 결과에 저장된 2단계(LLM) 판정을 얹어 계산합니다. LLM은 여기서 부르지 않습니다(캐시만 읽음).
     * semanticFailure: 백그라운드 판정에서 실패한 단계. 캐시에 없는 판정이 남으면 semanticStatus=PENDING입니다.
     */
    private Analysis compute(String id,String pid,JsonNode source,String semanticFailure,boolean withTeacherLabels) {
        var saved=source.path("aiDisclosure");boolean automatic="SYSTEM_GENERATED".equals(saved.path("source").asText());
        if(automatic)saved=source.path("studentAiDisclosure");
        var disclosure=saved.isObject()?new AssessmentAnalyzer.DisclosureInput(saved.path("tools").asText(),saved.path("usedParts").asText(),saved.path("reason").asText()):automatic?AssessmentAnalyzer.DisclosureInput.EMPTY:disclosure(pid);
        var input=withEvaluationContent(id,source);var now=Instant.now();
        var stage1=analyzer.analyze(pid,input,disclosure,now);
        var labels=withTeacherLabels?teacherLabels(pid):Map.<String,String>of();
        if(!judge.isActive()&&labels.isEmpty()) return stage1;
        var semantic=judge.isActive()?judge.cached(judge.plan(input,stage1),semanticFailure):SemanticInput.DISABLED;
        return analyzer.analyze(pid,input,disclosure,now,semantic,labels);
    }
    /**
     * 3단계: 교사가 문장 판정을 고칩니다(AI|DIRECT). 문장·교사 판정과 함께 그때의 알고리즘 판정·근거 값(최장 일치, 3-gram,
     * 두 모델 단계)·설정·프롬프트 버전을 저장하고, 그 학생 비율에 바로 반영한 분석을 돌려줍니다.
     */
    public Analysis labelSentence(String id,String pid,String sentenceId,SentenceLabelInput input) {
        String teacherId=teacher(id);person(id,pid);requireSubmitted(pid);
        if(db.queryForList("SELECT id FROM evaluations WHERE id=? FOR UPDATE",String.class,id).isEmpty()) throw fail(HttpStatus.NOT_FOUND,"EVALUATION_NOT_FOUND","평가를 찾을 수 없습니다.");
        var source=reportsWorkWithoutAuth(id,pid);
        var algorithm=compute(id,pid,source,null,false);
        var sentence=algorithm.sentences().stream().filter(s->s.id().equals(sentenceId)).findFirst()
                .orElseThrow(()->fail(HttpStatus.NOT_FOUND,"SENTENCE_NOT_FOUND","현재 제출본에서 해당 문장을 찾을 수 없습니다. 분석을 다시 조회해주세요."));
        var config=new LinkedHashMap<String,Object>(analyzer.getDefinition());config.putAll(judge.describe());config.put("semanticStatus",algorithm.semanticStatus());
        var semantic=sentence.semantic();
        db.update("INSERT INTO sentence_labels(evaluation_id,participant_id,question_id,sentence_id,sentence_text,teacher_label,algorithm_source,algorithm_reason,longest_span,similarity,gemini_level,sonnet_level,needs_review,config_json,teacher_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,pid,sentence.questionId(),sentence.id(),sentence.text(),input.label(),sentence.source(),sentence.reason(),sentence.longestSpan(),sentence.similarity(),
                semantic==null?null:semantic.geminiLevel(),semantic==null?null:semantic.sonnetLevel(),sentence.needsReview(),encode(config),teacherId,now());
        return analyzeAndStore(id,pid);
    }
    /** 답안 출처 판정 V2는 평가 안내 문구를 비교에서 빼므로, 제출 snapshot에 없으면 현재 평가 안내를 붙여 넘깁니다(저장하지 않음). */
    private JsonNode withEvaluationContent(String id,JsonNode source) {
        if(source.hasNonNull("evaluationContent")||!(source instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return source;
        var content=db.queryForList("SELECT evaluation_content FROM evaluations WHERE id=?",String.class,id);
        var copy=object.deepCopy();copy.put("evaluationContent",content.isEmpty()||content.get(0)==null?"":content.get(0));
        return copy;
    }
    /** 저장된 분석을 읽고, 없거나 PENDING/FAILED면 즉시 계산해 저장합니다. 미제출이면 null. */
    private Analysis stored(String id,String pid,boolean computeIfMissing) {
        if(!submitted(pid)) return null;
        var rows=db.query("SELECT analysis_status,result_json FROM grading_analyses WHERE participant_id=?",(r,n)->new String[]{r.getString("analysis_status"),r.getString("result_json")},pid);
        if(!rows.isEmpty()&&"DONE".equals(rows.get(0)[0])&&rows.get(0)[1]!=null) {var saved=decode(rows.get(0)[1],new TypeReference<Analysis>(){});if(AssessmentAnalyzer.MODEL.equals(saved.model())&&saved.semanticStatus()!=null&&judge.isActive()!=SemanticJudge.STATUS_DISABLED.equals(saved.semanticStatus()))return saved;}
        if(!computeIfMissing) return null;
        return analyzeAndStore(id,pid);
    }
    private Analysis analyzeAndStore(String id,String pid) {
        var result=compute(id,pid,reportsWorkWithoutAuth(id,pid));
        var analyzedAt=OffsetDateTime.ofInstant(result.analyzedAt(),ZoneOffset.UTC);
        int updated=db.update("UPDATE grading_analyses SET analysis_status='DONE',result_json=?,error_code=NULL,analyzed_at=?,semantic_status=? WHERE participant_id=?",encode(result),analyzedAt,result.semanticStatus(),pid);
        if(updated==0) db.update("INSERT INTO grading_analyses(participant_id,evaluation_id,analysis_status,result_json,requested_at,analyzed_at,semantic_status) VALUES (?,?,'DONE',?,?,?,?)",
                pid,id,encode(result),analyzedAt,analyzedAt,result.semanticStatus());
        return result;
    }
    public Map<String,Object> getAnalysisDefinition(String id){teacher(id);return analyzer.getDefinition();}
    public Analysis analysis(String id,String pid) {
        teacher(id);person(id,pid);
        var result=stored(id,pid,true);
        if(result!=null) return result;
        return new Analysis(pid,"NOT_SUBMITTED",AssessmentAnalyzer.MODEL,null,0,null,null,List.of(),List.of(),List.of(),List.of(),null,
                "학생이 아직 제출하지 않았습니다. 제출(자동 제출 포함) 후 분석합니다.",null,List.of());
    }
    public AnalysisRun run(String id) {
        teacher(id);
        var pids=db.queryForList("SELECT participant_id FROM submissions WHERE evaluation_id=?",String.class,id);
        for(String pid:pids) db.update("MERGE INTO grading_analyses(participant_id,evaluation_id,analysis_status,requested_at) KEY(participant_id) VALUES (?,?,'PENDING',?)",pid,id,now());
        return new AnalysisRun(id,pids.size(),"PENDING");
    }
    /** 스케줄러 전용(교사 인증 없음). 한 명씩 별도 트랜잭션으로 처리합니다. */
    public void analyzeForJob(String id,String pid,OffsetDateTime requestVersion) {
        // Serialize with submission refresh and teacher operations; never publish an older snapshot over a newer request.
        if(db.queryForList("SELECT id FROM evaluations WHERE id=? FOR UPDATE",String.class,id).isEmpty())return;
        person(id,pid);
        var pending=db.query("SELECT analysis_status,requested_at FROM grading_analyses WHERE participant_id=?",(r,n)->new Object[]{r.getString("analysis_status"),r.getObject("requested_at",OffsetDateTime.class)},pid);
        if(pending.isEmpty()||!pending.get(0)[0].equals("PENDING")||!((OffsetDateTime)pending.get(0)[1]).isEqual(requestVersion))return;
        var src=reportsWorkWithoutAuth(id,pid);
        var result=compute(id,pid,src);
        db.update("UPDATE grading_analyses SET analysis_status='DONE',result_json=?,error_code=NULL,analyzed_at=?,semantic_status=? WHERE participant_id=?",
                encode(result),OffsetDateTime.ofInstant(result.analyzedAt(),ZoneOffset.UTC),result.semanticStatus(),pid);
    }
    /** 스케줄러 전용: 2단계 판정이 필요한(semantic_status=PENDING) 분석의 LLM 후보를 만듭니다. 대상이 아니면 null. */
    public SemanticJudge.Plan prepareSemantic(String id,String pid) {
        var rows=db.queryForList("SELECT participant_id FROM grading_analyses WHERE participant_id=? AND evaluation_id=? AND analysis_status='DONE' AND semantic_status='PENDING'",String.class,pid,id);
        if(rows.isEmpty()||!judge.isActive()) return null;
        var input=withEvaluationContent(id,reportsWorkWithoutAuth(id,pid));
        var saved=input.path("aiDisclosure");
        var disclosure=new AssessmentAnalyzer.DisclosureInput(saved.path("tools").asText(""),saved.path("usedParts").asText(""),saved.path("reason").asText(""));
        return judge.plan(input,analyzer.analyze(pid,input,disclosure,Instant.now()));
    }
    /** 스케줄러 전용: LLM 호출이 끝난 뒤(트랜잭션 밖) 저장된 판정으로 다시 계산해 저장합니다. 그사이 제출본이 바뀌어도 최신 제출본으로 계산합니다. */
    public void storeSemantic(String id,String pid,String failure) {
        if(db.queryForList("SELECT id FROM evaluations WHERE id=? FOR UPDATE",String.class,id).isEmpty())return;
        var rows=db.queryForList("SELECT participant_id FROM grading_analyses WHERE participant_id=? AND evaluation_id=? AND analysis_status='DONE' AND semantic_status='PENDING'",String.class,pid,id);
        if(rows.isEmpty()) return;
        var result=compute(id,pid,reportsWorkWithoutAuth(id,pid),failure);
        // 실패 없이도 캐시가 채워지지 않았다면(예: 그사이 제출본 변경) PENDING으로 남아 다음 주기에 다시 판정합니다.
        db.update("UPDATE grading_analyses SET result_json=?,analyzed_at=?,semantic_status=? WHERE participant_id=?",
                encode(result),OffsetDateTime.ofInstant(result.analyzedAt(),ZoneOffset.UTC),result.semanticStatus(),pid);
    }
    public void markSemanticUnavailable(String pid) {
        db.update("UPDATE grading_analyses SET semantic_status='UNAVAILABLE' WHERE participant_id=? AND semantic_status='PENDING'",pid);
    }
    public void markFailed(String pid,OffsetDateTime requestVersion,String code) {
        db.update("UPDATE grading_analyses SET analysis_status='FAILED',error_code=? WHERE participant_id=? AND analysis_status='PENDING' AND requested_at=?",code,pid,requestVersion);
    }
    private JsonNode reportsWorkWithoutAuth(String id,String pid) {
        // 제출된 참가자만 분석하므로 snapshot을 직접 읽습니다.
        var rows=db.queryForList("SELECT snapshot FROM submissions WHERE participant_id=? AND evaluation_id=?",String.class,pid,id);
        if(rows.isEmpty()) throw fail(HttpStatus.CONFLICT,"SUBMISSION_REQUIRED","제출 기록이 없습니다.");
        try { return json.readTree(rows.get(0)); } catch(Exception e) { throw new IllegalStateException(e); }
    }

    // ===== 점수 =====
    private record TeacherRow(int criterionId,int level,String feedback,Instant updatedAt) {}
    private record ResultRow(String status,Double total,Instant confirmedAt,String decision,String note) {}
    private Map<Integer,TeacherRow> teacherRows(String pid) {
        var map=new HashMap<Integer,TeacherRow>();
        db.query("SELECT * FROM grading_scores WHERE participant_id=?",r->{map.put(r.getInt("criterion_id"),new TeacherRow(r.getInt("criterion_id"),r.getInt("score_level"),r.getString("feedback"),instant(r,"updated_at")));},pid);
        return map;
    }
    private ResultRow resultRow(String pid) {
        var rows=db.query("SELECT * FROM grading_results WHERE participant_id=?",(r,n)->{
            BigDecimal t=r.getBigDecimal("total_score");
            return new ResultRow(r.getString("grading_status"),t==null?null:t.doubleValue(),instant(r,"confirmed_at"),r.getString("compliance_decision"),r.getString("compliance_note"));
        },pid);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Scores scoresView(String id,String pid,Analysis analysis) {
        var list=criteria(id);var teacherRows=teacherRows(pid);var result=resultRow(pid);
        var indicators=new EnumMap<Indicator,IndicatorResult>(Indicator.class);
        if(analysis!=null&&analysis.indicators()!=null) for(var i:analysis.indicators()) indicators.put(i.indicator(),i);
        var out=new ArrayList<CriterionScore>();double ai=0,teacher=0,current=0;boolean allTeacher=true,anyAi=false;int recommendedMax=0;var missingAi=new ArrayList<String>();
        for(var c:list) {
            var ind=c.indicator()==Indicator.MANUAL?null:indicators.get(c.indicator());
            AiScore aiScore=ind==null||ind.level()==null?null:new AiScore(ind.level(),score(c.maxScore(),ind.level()),ind.reason(),ind.evidence(),ind.reasons());
            var row=teacherRows.get(c.criterionId());
            TeacherScore t=row==null?null:new TeacherScore(row.level(),score(c.maxScore(),row.level()),row.feedback(),row.updatedAt());
            if(aiScore!=null) { ai+=aiScore.score();anyAi=true;recommendedMax+=c.maxScore(); } else missingAi.add(c.name());
            if(t!=null) teacher+=t.score(); else allTeacher=false;
            current+=t!=null?t.score():aiScore!=null?aiScore.score():0;
            out.add(new CriterionScore(c.criterionId(),c.name(),c.maxScore(),c.indicator(),aiScore,t,t!=null&&aiScore!=null&&t.level()!=aiScore.level()));
        }
        String status=result!=null&&"CONFIRMED".equals(result.status())?"CONFIRMED":!teacherRows.isEmpty()?"DRAFT":"NOT_STARTED";
        var compliance=new ComplianceReview(analysis==null||analysis.compliance()==null?null:analysis.compliance().result(),
                result==null||result.decision()==null?null:ComplianceDecision.valueOf(result.decision()),result==null?null:result.note());
        return new Scores(pid,out,anyAi?round2(ai):null,allTeacher&&!list.isEmpty()?round2(teacher):null,round2(current),status,
                result==null?null:result.total(),result==null?null:result.confirmedAt(),analysis==null?"NOT_SUBMITTED":analysis.status(),compliance,recommendedMax,missingAi,analysis==null?"제출 후 분석할 수 있습니다.":analysis.summary());
    }
    private static double round2(double v) { return Math.round(v*100)/100.0; }
    public Scores scores(String id,String pid) { teacher(id);person(id,pid);return scoresView(id,pid,stored(id,pid,true)); }
    public Scores saveScore(String id,String pid,int criterionId,ScoreInput input) {
        String teacherId=teacher(id);person(id,pid);requireSubmitted(pid);
        var criterion=criteria(id).stream().filter(c->c.criterionId()==criterionId).findFirst()
                .orElseThrow(()->fail(HttpStatus.NOT_FOUND,"CRITERION_NOT_FOUND","평가 기준을 찾을 수 없습니다."));
        db.queryForList("SELECT id FROM participants WHERE id=? FOR UPDATE",pid);
        var analysis=stored(id,pid,true);
        var current=teacherRows(pid).get(criterionId);
        Integer before=current==null?null:current.level();Integer after;
        if(input.level()==null&&input.feedback()==null) {
            db.update("DELETE FROM grading_scores WHERE participant_id=? AND criterion_id=?",pid,criterionId);after=null;
        } else if(input.level()==null) {
            if(current==null) throw fail(HttpStatus.BAD_REQUEST,"LEVEL_REQUIRED","처음 저장할 때는 level(0~4)이 필요합니다.");
            db.update("UPDATE grading_scores SET feedback=?,teacher_id=?,updated_at=? WHERE participant_id=? AND criterion_id=?",input.feedback(),teacherId,now(),pid,criterionId);after=before;
        } else {
            String feedback=input.feedback()!=null?input.feedback():current==null?"":current.feedback();
            db.update("MERGE INTO grading_scores(participant_id,criterion_id,evaluation_id,score_level,feedback,teacher_id,updated_at) KEY(participant_id,criterion_id) VALUES (?,?,?,?,?,?,?)",
                    pid,criterionId,id,input.level(),feedback,teacherId,now());after=input.level();
        }
        String previousFeedback=current==null?null:current.feedback();
        String feedback=after==null?null:input.feedback()!=null?input.feedback():current==null?"":current.feedback();
        if(!Objects.equals(before,after)||!Objects.equals(previousFeedback,feedback)) {
            Integer aiLevel=null;
            if(analysis!=null&&criterion.indicator()!=Indicator.MANUAL) for(var i:analysis.indicators()) if(i.indicator()==criterion.indicator()) aiLevel=i.level();
            db.update("INSERT INTO grading_score_changes(evaluation_id,participant_id,criterion_id,previous_level,score_level,ai_level,teacher_id,changed_at,previous_feedback,feedback) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id,pid,criterionId,before,after,aiLevel,teacherId,now(),previousFeedback,feedback);
            // 확정 뒤 점수를 바꾸면 DRAFT로 돌아가며, 재확정해야 합니다. 마지막 확정 총점은 confirmedTotal로 남습니다.
            setStatus(pid,id,"DRAFT");
        }
        return scoresView(id,pid,analysis);
    }
    /** 결과 행이 없으면 만들고 상태만 바꿉니다. 마지막 확정 총점·시각은 유지합니다. */
    private void setStatus(String pid,String id,String status) {
        if(db.update("UPDATE grading_results SET grading_status=?,updated_at=? WHERE participant_id=?",status,now(),pid)==0)
            db.update("INSERT INTO grading_results(participant_id,evaluation_id,grading_status,updated_at) VALUES (?,?,?,?)",pid,id,status,now());
    }
    public Scores confirm(String id,String pid,boolean acceptAiForMissing) {
        String teacherId=teacher(id);person(id,pid);requireSubmitted(pid);
        db.queryForList("SELECT id FROM participants WHERE id=? FOR UPDATE",pid);
        var analysis=stored(id,pid,true);
        if(acceptAiForMissing) {
            var view=scoresView(id,pid,analysis);
            for(var c:view.criteria()) if(c.teacher()==null&&c.ai()!=null) {
                db.update("INSERT INTO grading_scores(participant_id,criterion_id,evaluation_id,score_level,feedback,teacher_id,updated_at) VALUES (?,?,?,?,?,?,?)",
                        pid,c.criterionId(),id,c.ai().level(),"",teacherId,now());
                db.update("INSERT INTO grading_score_changes(evaluation_id,participant_id,criterion_id,previous_level,score_level,ai_level,teacher_id,changed_at,previous_feedback,feedback) VALUES (?,?,?,NULL,?,?,?,?,NULL,?)",
                        id,pid,c.criterionId(),c.ai().level(),c.ai().level(),teacherId,now(),"");
            }
        }
        var view=scoresView(id,pid,analysis);
        var missing=view.criteria().stream().filter(c->c.teacher()==null).toList();
        if(!missing.isEmpty()) throw new ApiError.Failure(HttpStatus.CONFLICT,"GRADING_INCOMPLETE","모든 기준을 채점해야 확정할 수 있습니다. 미입력: "+String.join(", ",missing.stream().map(CriterionScore::name).toList()),Map.of("missingCriteria",missing.stream().map(c->Map.of("criterionId",c.criterionId(),"name",c.name())).toList()));
        boolean alreadyConfirmed="CONFIRMED".equals(view.status());
        setStatus(pid,id,"CONFIRMED");
        db.update("UPDATE grading_results SET total_score=?,confirmed_by=?,confirmed_at=?,updated_at=? WHERE participant_id=?",
                BigDecimal.valueOf(view.totalTeacher()),teacherId,now(),now(),pid);
        if(!alreadyConfirmed) {
            Long revision=db.queryForObject("SELECT COALESCE(MAX(id),0) FROM grading_score_changes WHERE participant_id=?",Long.class,pid);
            String nickname=db.queryForObject("SELECT nickname FROM participants WHERE id=?",String.class,pid);
            notifications.notifyOwner(id,NotificationService.Type.GRADING_CONFIRMED,"채점 확정",nickname+" 학생의 채점이 확정되었습니다.",pid,"grading-confirmed:"+pid+":"+revision);
        }
        return scoresView(id,pid,analysis);
    }
    public Scores reviewCompliance(String id,String pid,ComplianceInput input) {
        teacher(id);person(id,pid);requireSubmitted(pid);
        var result=resultRow(pid);
        setStatus(pid,id,result==null?"DRAFT":result.status());
        db.update("UPDATE grading_results SET compliance_decision=?,compliance_note=?,updated_at=? WHERE participant_id=?",
                input.decision().name(),input.note()==null?"":input.note().strip(),now(),pid);
        return scoresView(id,pid,stored(id,pid,true));
    }
    public List<ScoreChange> history(String id,String pid,long afterId,int limit) {
        teacher(id);person(id,pid);
        if(afterId<0||limit<1||limit>200) throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","이력 조회 범위를 확인해주세요.");
        return db.query("SELECT * FROM grading_score_changes WHERE participant_id=? AND id>? ORDER BY id LIMIT ?",(r,n)->new ScoreChange(r.getLong("id"),r.getInt("criterion_id"),
                (Integer)r.getObject("previous_level"),(Integer)r.getObject("score_level"),(Integer)r.getObject("ai_level"),r.getString("teacher_id"),instant(r,"changed_at"),r.getString("previous_feedback"),r.getString("feedback")),pid,afterId,limit);
    }

    // ===== 제출 현황 =====
    public Submissions submissions(String id,int page,int size,String query) {
        teacher(id);
        if(page<0||size<1||size>200||query==null||query.length()>100) throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","page는 0 이상, size는 1~200, query는 100자 이하입니다.");
        String q=query.strip();
        String filter=" FROM participants p WHERE p.evaluation_id=? AND LOCATE(LOWER(?),LOWER(p.nickname))>0";
        long total=db.queryForObject("SELECT COUNT(*)"+filter,Long.class,id,q);
        String warn="(SELECT COUNT(*) FROM learning_events e WHERE e.participant_id=p.id AND e.event_type IN ("+WorkModels.ClientEventType.warningSql()+"))";
        long warning=db.queryForObject("SELECT COUNT(*)"+filter+" AND "+warn+">0",Long.class,id,q);
        long submittedCount=db.queryForObject("SELECT COUNT(*)"+filter+" AND "+warn+"=0 AND EXISTS (SELECT 1 FROM submissions s WHERE s.participant_id=p.id)",Long.class,id,q);
        long confirmed=db.queryForObject("SELECT COUNT(*)"+filter+" AND EXISTS (SELECT 1 FROM grading_results g WHERE g.participant_id=p.id AND g.grading_status='CONFIRMED')",Long.class,id,q);
        var rows=db.query("SELECT p.id,p.nickname,p.expelled_at,s.submitted_at,s.submission_mode,"+warn+" warnings,(SELECT MAX(e.created_at) FROM learning_events e WHERE e.participant_id=p.id) last_event_at FROM participants p LEFT JOIN submissions s ON s.participant_id=p.id"
                        +" WHERE p.evaluation_id=? AND LOCATE(LOWER(?),LOWER(p.nickname))>0 ORDER BY p.nickname,p.id LIMIT ? OFFSET ?",
                (r,n)->new Object[]{r.getString("id"),r.getString("nickname"),instant(r,"submitted_at"),r.getString("submission_mode"),r.getInt("warnings"),instant(r,"last_event_at"),instant(r,"expelled_at")},id,q,size,(long)page*size);
        var items=new ArrayList<SubmissionRow>();
        for(var row:rows) {
            String pid=(String)row[0];Instant at=(Instant)row[2];int warnings=(Integer)row[4];boolean done=at!=null;
            Analysis analysis=null;
            if(done) {
                try { analysis=stored(id,pid,true); }
                catch(RuntimeException ex) { log.warn("Analysis failed in list: participant={}, type={}",pid,ex.getClass().getSimpleName()); }
            }
            var view=scoresView(id,pid,analysis);
            Double totalScore="CONFIRMED".equals(view.status())?view.confirmedTotal():null;
            items.add(new SubmissionRow(pid,(String)row[1],(String)row[1],done?"SUBMITTED":"WRITING",done,
                    done?"SUBMITTED":"NOT_SUBMITTED",at,(String)row[3],warnings,view.status(),totalScore,view.totalAi(),
                    analysis==null||analysis.compliance()==null?null:analysis.compliance().result(),done?(analysis==null?"FAILED":analysis.status()):"NOT_SUBMITTED",view.recommendedMaxScore(),(Instant)row[5],db.queryForObject("SELECT MAX(created_at) last_warning_at FROM learning_events WHERE participant_id=? AND event_type IN ("+WorkModels.ClientEventType.warningSql()+")",(r,n)->instant(r,"last_warning_at"),pid),row[6]!=null,(Instant)row[6]));
        }
        return new Submissions(id,items,page,size,total,(total+size-1)/size,new SubmissionCounts(submittedCount,warning,total-warning-submittedCount,confirmed));
    }

    // ===== 답안·대화 =====
    public Answers answers(String id,String pid) {
        teacher(id);person(id,pid);
        var source=reports.work(id,pid);
        var analysis=compute(id,pid,source);
        var byQuestion=new LinkedHashMap<String,List<Paragraph>>();
        for(var p:analysis.paragraphs()) byQuestion.computeIfAbsent(p.questionId(),k->new ArrayList<>()).add(p);
        var questions=new ArrayList<AnswerQuestion>();
        for(var entry:source.path("answers")) {
            var q=entry.path("question");var a=entry.path("answer");String qid=q.path("id").asText();
            Instant updated=null;
            try { if(!a.path("updatedAt").isNull()&&!a.path("updatedAt").isMissingNode()) updated=Instant.parse(a.path("updatedAt").asText()); } catch(DateTimeException ignored) {}
            var paragraphs=byQuestion.getOrDefault(qid,List.of());
            questions.add(new AnswerQuestion(qid,q.path("position").asInt(),q.path("title").asText(),q.path("content").asText(),a.path("content").asText(""),
                    a.path("version").asInt(),updated,paragraphs.isEmpty()?null:paragraphs.get(0).declaredSource(),paragraphs,q.path("minChars").asInt()>0?q.path("minChars").asInt():null,q.path("maxChars").asInt()>0?q.path("maxChars").asInt():null));
        }
        var sub=db.query("SELECT submitted_at,submission_mode FROM submissions WHERE participant_id=?",(r,n)->new Object[]{instant(r,"submitted_at"),r.getString("submission_mode")},pid);
        String nickname=db.queryForObject("SELECT nickname FROM participants WHERE id=?",String.class,pid);
        return new Answers(pid,nickname,sub.isEmpty()?"WRITING":"SUBMITTED",sub.isEmpty()?null:(Instant)sub.get(0)[0],sub.isEmpty()?null:(String)sub.get(0)[1],questions,true);
    }
    public List<Conversation> conversations(String id,String pid,String questionId,int page,int size) {
        teacher(id);person(id,pid);
        if(page<0||size<1||size>200) throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","page는 0 이상, size는 1~200입니다.");
        var source=reports.work(id,pid);
        if(questionId!=null) {
            boolean found=false;for(var e:source.path("answers")) if(e.path("question").path("id").asText().equals(questionId)) found=true;
            if(!found) throw fail(HttpStatus.NOT_FOUND,"QUESTION_NOT_FOUND","해당 평가의 문항이 아닙니다.");
        }
        var out=new ArrayList<Conversation>();
        for(var t:source.path("conversations")) {
            String qid=t.path("questionId").asText();if(questionId!=null&&!questionId.equals(qid)) continue;
            var type=AssessmentAnalyzer.classify(t.path("questionText").asText());
            out.add(new Conversation(t.path("id").asLong(),t.path("requestId").asText(),qid,t.path("questionText").asText(),
                    t.path("answerText").isNull()?null:t.path("answerText").asText(null),t.path("state").asText(),t.path("errorCode").isNull()?null:t.path("errorCode").asText(null),
                    parse(t.path("createdAt")),parse(t.path("completedAt")),type,type.label()));
        }
        out.sort(Comparator.comparingLong(Conversation::conversationId));
        return out.stream().skip((long)page*size).limit(size).toList();
    }
    private static Instant parse(JsonNode node) {
        if(node==null||node.isNull()||node.isMissingNode()) return null;
        try { return Instant.parse(node.asText()); } catch(DateTimeException e) { return null; }
    }
}
