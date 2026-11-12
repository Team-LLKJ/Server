package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ProcessGradingModels {
    private ProcessGradingModels() {}
    public static final String LEGACY_RUBRIC_VERSION="LIT_PROCESS_RUBRIC_V1";
    public static final String PREVIOUS_RUBRIC_VERSION="LIT_PROCESS_RUBRIC_V2";
    public static final String RUBRIC_VERSION="LIT_PROCESS_RUBRIC_V3";
    public static final String GLOBAL="GLOBAL";
    public enum Criterion { PURPOSE, SELF_DIRECTION, CRITICAL_REVIEW, CONTRIBUTION, TRANSPARENCY;
        public String displayName(){return switch(this){case PURPOSE->"AI 활용 목적의 적절성";case SELF_DIRECTION->"자기 주도적 사고";case CRITICAL_REVIEW->"AI 답변의 비판적 검토";case CONTRIBUTION->"최종 답안에 대한 학생의 기여";case TRANSPARENCY->"AI 활용 과정의 투명성";};}
        public int maxScore(){return switch(this){case PURPOSE,TRANSPARENCY->15;case SELF_DIRECTION,CONTRIBUTION->25;case CRITICAL_REVIEW->20;};}
    }
    public enum NoAiPolicy { HOLD, NOT_APPLICABLE, USE_RECORDED_EVIDENCE }
    public record QuestionWeight(@NotBlank String questionId,@NotNull @DecimalMin("0.0001") @DecimalMax("100") @Digits(integer=3,fraction=4) BigDecimal weight) {}
    public record PolicyRequest(@Min(0) int expectedVersion,@NotBlank @Size(max=10000) String taskGoal,
            @NotBlank @Size(max=10000) String allowedRules,@NotNull NoAiPolicy noAiPolicy,
            @NotEmpty @Size(max=100) List<@NotNull @Valid QuestionWeight> questionWeights,@Size(max=5) List<@NotNull Criterion> excludedCriteria) {
        public PolicyRequest(int version,String goal,String rules,NoAiPolicy policy,List<QuestionWeight> weights){this(version,goal,rules,policy,weights,List.of());}
    }
    public record Policy(int version,String rubricVersion,String taskGoal,String allowedRules,NoAiPolicy noAiPolicy,List<QuestionWeight> questionWeights,List<Criterion> excludedCriteria) {
        public Policy {excludedCriteria=excludedCriteria==null?List.of():List.copyOf(excludedCriteria);}
        public Policy(int version,String rubricVersion,String taskGoal,String rules,NoAiPolicy policy,List<QuestionWeight> weights){this(version,rubricVersion,taskGoal,rules,policy,weights,List.of());}
    }
    public record Source(String id,String questionId,String kind,String text,String occurredAt,Integer answerVersion,Long conversationId,String messageRole,Integer startOffset) {
        public Source(String id,String questionId,String kind,String text,String occurredAt,Integer answerVersion){this(id,questionId,kind,text,occurredAt,answerVersion,null,null,null);}
        public Source(String id,String questionId,String kind,String text){this(id,questionId,kind,text,null,null);}
    }
    public record Input(String evaluationId,String participantId,String submissionHash,String title,String aiPolicy,
                        Policy policy,List<String> questionIds,List<Source> sources,List<String> limitations,AnalysisScope analysisScope,InputPathAnalyzer.View inputPaths,PurposeUsageAnalyzer.View usageAnalysis) {
        public Input(String id,String pid,String hash,String title,String aiPolicy,Policy policy,List<String> ids,List<Source> sources,List<String> limits){this(id,pid,hash,title,aiPolicy,policy,ids,sources,limits,null,null,null);}
    }
    public record AnswerVersion(String questionId,int answerVersion) {}
    public record AnalysisScope(List<String> questionIds,List<AnswerVersion> answerVersions,List<Long> conversationIds,List<String> missingData) {}
    public record EvidenceLocation(String questionId,Long conversationId,String messageRole,Integer answerVersion,String textScope,int start,int end) {}
    public record Citation(String sourceId,String quote,Integer start,Integer end,EvidenceLocation location) {
        public Citation(String sourceId,String quote){this(sourceId,quote,null,null,null);}
    }
    public record BehaviorReason(String title,String explanation,List<Citation> evidence) {}
    public record ConditionFinding(String key,String status,boolean essential,String explanation,List<Citation> evidence) {}
    public record Details(List<BehaviorReason> credits,List<BehaviorReason> deductions,List<String> confirmationNeeded,List<String> improvements,List<ConditionFinding> findings) {}
    public record Condition(String key,List<Citation> evidence) {}
    public record Recommendation(String questionId,Criterion criterion,Integer level,String reason,String unmetNextLevel,
                                 List<Condition> conditions,List<Citation> counterEvidence,List<String> limitations,Details details) {
        public Recommendation(String questionId,Criterion criterion,Integer level,String reason,String next,List<Condition> conditions,List<Citation> counter,List<String> limits){this(questionId,criterion,level,reason,next,conditions,counter,limits,null);}
    }
    public record Compliance(String status,String reason,List<Citation> evidence,List<String> limitations) {}
    public record AiOutput(Compliance compliance,List<Recommendation> recommendations,String summary) {
        public AiOutput(Compliance compliance,List<Recommendation> recommendations){this(compliance,recommendations,null);}
    }
    /**
     * LLM 루브릭 판정 정보(학생 단위 칸에만). votes: Gemini 회차별 단계(무효 회차 null, "NA"·"HOLD" 가능), haikuLevel: 엇갈렸을 때 Haiku 답,
     * needsReview: 교사 직접 입력 필요(reviewReason: DISAGREEMENT | INSUFFICIENT_DATA | NO_AI_USE | SAVE_ERROR), runs: 회차별 원래 답(근거 포함, 보정용).
     */
    public record JudgeInfo(@io.swagger.v3.oas.annotations.media.Schema(description="Gemini 회차별 단계(1~5 정수, \"NA\", \"HOLD\", 근거 없는 무효 회차는 null)") List<Object> votes,
                            @io.swagger.v3.oas.annotations.media.Schema(description="Gemini가 엇갈렸을 때 Haiku 답(1~5, \"NA\", \"HOLD\"), 아니면 null") Object haikuLevel,
                            @io.swagger.v3.oas.annotations.media.Schema(description="교사 직접 입력이 필요한지(교사가 확정하면 false)") boolean needsReview,
                            @io.swagger.v3.oas.annotations.media.Schema(description="needsReview 이유. DISAGREEMENT: Gemini 3번·Haiku로도 엇갈림, INSUFFICIENT_DATA: 대화가 너무 적음(모델 호출 안 함), NO_AI_USE: AI 미사용 학생의 자기 주도적 사고(교사 확인, HOLD), SAVE_ERROR: 활용 설명란 저장 오류(투명성 HOLD)",allowableValues={"DISAGREEMENT","INSUFFICIENT_DATA","NO_AI_USE","SAVE_ERROR"},nullable=true) String reviewReason,
                            @io.swagger.v3.oas.annotations.media.Schema(allowableValues={"LLM_GEMINI_X3","LLM_GEMINI_X3_HAIKU","SERVER_RULE"}) String method,List<RubricJudge.Run> runs,
                            String promptVersion,String geminiModel,String haikuModel,
                            @io.swagger.v3.oas.annotations.media.Schema(description="Gemini가 엇갈렸는데 Haiku 판정이 없는 이유(NOT_CONFIGURED: Anthropic 키 없음, NO_VERIFIED_EVIDENCE: Haiku 인용을 원문에서 2번 모두 못 찾음, 그 외 호출 오류 코드)",nullable=true) String haikuError) {
        /** 교사가 확정한 뒤에는 확인 필요를 풉니다(나머지 기록은 보존). */
        public JudgeInfo reviewed(){return new JudgeInfo(votes,haikuLevel,false,reviewReason,method,runs,promptVersion,geminiModel,haikuModel,haikuError);}
    }
    /**
     * 채점 한 칸. 문항별 채점은 questionId가 문항 id(투명성은 GLOBAL), LLM 루브릭 판정(학생 단위)은 questionId=null이고 항목마다 한 칸입니다.
     * 학생 단위 칸의 adjustedMaxScore는 NA 재분배 후 배점(정수, NA 칸은 0)이고 score는 그 배점 기준 정수 점수입니다. 문항별 채점은 adjustedMaxScore=null.
     */
    public record Cell(@io.swagger.v3.oas.annotations.media.Schema(description="문항 id. 투명성은 GLOBAL, 학생 단위(LLM 루브릭)는 null",nullable=true) String questionId,Criterion criterion,
                       @io.swagger.v3.oas.annotations.media.Schema(description="단계 1~5(확정 전·HOLD·NA는 null)",nullable=true) Integer level,
                       @io.swagger.v3.oas.annotations.media.Schema(description="점수. 학생 단위는 adjustedMaxScore 기준 정수(HOLD·NA는 null)",nullable=true) BigDecimal score,
                       @io.swagger.v3.oas.annotations.media.Schema(allowableValues={"RECOMMENDED","HOLD","NOT_APPLICABLE","TEACHER_REVIEWED"}) String status,String reason,
                       String unmetNextLevel,List<Condition> conditions,List<Citation> counterEvidence,List<String> limitations,Details details,
                       @io.swagger.v3.oas.annotations.media.Schema(description="LLM 루브릭 판정 정보(학생 단위 칸에만)",nullable=true) JudgeInfo judge,
                       @io.swagger.v3.oas.annotations.media.Schema(description="학생 단위: NA 재분배 후 배점(정수, NA 칸은 0). 문항별 채점은 null",nullable=true) Integer adjustedMaxScore) {
        public Cell(String questionId,Criterion criterion,Integer level,BigDecimal score,String status,String reason,String next,List<Condition> conditions,List<Citation> counter,List<String> limits){this(questionId,criterion,level,score,status,reason,next,conditions,counter,limits,null,null,null);}
        public Cell(String questionId,Criterion criterion,Integer level,BigDecimal score,String status,String reason,String next,List<Condition> conditions,List<Citation> counter,List<String> limits,Details details){this(questionId,criterion,level,score,status,reason,next,conditions,counter,limits,details,null,null);}
        public Cell(String questionId,Criterion criterion,Integer level,BigDecimal score,String status,String reason,String next,List<Condition> conditions,List<Citation> counter,List<String> limits,Details details,JudgeInfo judge){this(questionId,criterion,level,score,status,reason,next,conditions,counter,limits,details,judge,null);}
        Cell withScore(BigDecimal score,Integer adjustedMax){return new Cell(questionId,criterion,level,score,status,reason,unmetNextLevel,conditions,counterEvidence,limitations,details,judge,adjustedMax);}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public int maxScore(){return criterion.maxScore();}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String criterionName(){return criterion.displayName();}
    }
    /**
     * 부분 점수. 문항별 채점은 문항 가중치를 곱한 값입니다. 학생 단위(LLM 루브릭)는 모두 정수: NA 재분배 후 배점(adjustedMaxScore) 기준
     * assessedScore=판정된 칸 점수 합, assessedMaxScore=그 칸들의 배점 합, heldMaxScore=HOLD 칸 배점 합(교사가 채울 때까지 비움),
     * notApplicableMaxScore=NA 칸의 원래 배점 합입니다.
     */
    public record PartialScore(@io.swagger.v3.oas.annotations.media.Schema(description="판정된 칸 점수 합(학생 단위는 정수)") BigDecimal assessedScore,
                               @io.swagger.v3.oas.annotations.media.Schema(description="판정된 칸의 배점 합(학생 단위는 재분배 배점, 정수)") BigDecimal assessedMaxScore,
                               @io.swagger.v3.oas.annotations.media.Schema(description="HOLD 칸의 배점 합(교사가 채울 때까지 비움, 재분배하지 않음)") BigDecimal heldMaxScore,
                               @io.swagger.v3.oas.annotations.media.Schema(description="NA 칸의 원래 배점 합") BigDecimal notApplicableMaxScore) {}
    public record Result(String status,Compliance compliance,List<Cell> cells,BigDecimal total,List<String> limitations,String summary,PartialScore partialScore) {
        public Result(String status,Compliance compliance,List<Cell> cells,BigDecimal total,List<String> limitations){this(status,compliance,cells,total,limitations,null,null);}
    }
    public record Job(String id,String evaluationId,String participantId,int version,String status,String inputHash,
                      String rubricVersion,String model,Instant createdAt,Instant completedAt,Result result,String errorCode) {}
    /**
     * 교사 검토 한 칸. 수준(level)이나 점수(score) 중 하나만 보냅니다. 학생 단위 결과(LLM 루브릭)는 questionId를 null로 보냅니다.
     * score는 그 영역 만점 이하(소수 둘째 자리까지)로, AI 추천 점수를 그대로 덮어씁니다. 이때 칸의 level은 null입니다.
     */
    public record LevelOverride(@Size(max=100) String questionId,@NotNull Criterion criterion,@Min(0) @Max(5) Integer level,
                           @Size(max=2000) String reason,
                           @DecimalMin("0") @DecimalMax("25") @Digits(integer=2,fraction=2) BigDecimal score) {
        public LevelOverride { reason=reason==null?"":reason.strip(); }
        public LevelOverride(String questionId,Criterion criterion,Integer level,String reason){this(questionId,criterion,level,reason,null);}
        public static LevelOverride ofScore(String questionId,Criterion criterion,BigDecimal score,String reason){return new LevelOverride(questionId,criterion,null,reason,score);}
        @com.fasterxml.jackson.annotation.JsonIgnore public boolean isDirectScore(){return score!=null;}
    }
    public record ReviewRequest(@Min(0) int expectedRevision,@NotNull @Size(max=401) List<@NotNull @Valid LevelOverride> overrides,
                                @Pattern(regexp="HOLD|CONFIRMED_OK|CONFIRMED_VIOLATION") String complianceDecision,
                                @Size(max=2000) String complianceReason) {}
    public record ConfirmRequest(@Min(1) int expectedRevision) {}
    public record Review(int revision,String teacherId,Instant reviewedAt,List<LevelOverride> overrides,String complianceDecision,
                         String complianceReason,Result result,boolean confirmed) {}
    public record JobTarget(String id,Input input) {}
}
