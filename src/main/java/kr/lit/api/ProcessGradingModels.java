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
    public record Cell(String questionId,Criterion criterion,Integer level,BigDecimal score,String status,String reason,
                       String unmetNextLevel,List<Condition> conditions,List<Citation> counterEvidence,List<String> limitations,Details details) {
        public Cell(String questionId,Criterion criterion,Integer level,BigDecimal score,String status,String reason,String next,List<Condition> conditions,List<Citation> counter,List<String> limits){this(questionId,criterion,level,score,status,reason,next,conditions,counter,limits,null);}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public int maxScore(){return criterion.maxScore();}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String criterionName(){return criterion.displayName();}
    }
    public record PartialScore(BigDecimal assessedScore,BigDecimal assessedMaxScore,BigDecimal heldMaxScore,BigDecimal notApplicableMaxScore) {}
    public record Result(String status,Compliance compliance,List<Cell> cells,BigDecimal total,List<String> limitations,String summary,PartialScore partialScore) {
        public Result(String status,Compliance compliance,List<Cell> cells,BigDecimal total,List<String> limitations){this(status,compliance,cells,total,limitations,null,null);}
    }
    public record Job(String id,String evaluationId,String participantId,int version,String status,String inputHash,
                      String rubricVersion,String model,Instant createdAt,Instant completedAt,Result result,String errorCode) {}
    /**
     * 교사 검토 한 칸. 수준(level)이나 점수(score) 중 하나만 보냅니다.
     * score는 그 영역 만점 이하(소수 둘째 자리까지)로, AI 추천 점수를 그대로 덮어씁니다. 이때 칸의 level은 null입니다.
     */
    public record LevelOverride(@NotBlank String questionId,@NotNull Criterion criterion,@Min(0) @Max(5) Integer level,
                           @NotBlank @Size(max=2000) String reason,
                           @DecimalMin("0") @DecimalMax("25") @Digits(integer=2,fraction=2) BigDecimal score) {
        public LevelOverride(String questionId,Criterion criterion,Integer level,String reason){this(questionId,criterion,level,reason,null);}
        public static LevelOverride ofScore(String questionId,Criterion criterion,BigDecimal score,String reason){return new LevelOverride(questionId,criterion,null,reason,score);}
        @com.fasterxml.jackson.annotation.JsonIgnore public boolean isDirectScore(){return score!=null;}
    }
    public record ReviewRequest(@Min(0) int expectedRevision,@NotNull @Size(max=401) List<@NotNull @Valid LevelOverride> overrides,
                                @NotBlank @Pattern(regexp="HOLD|CONFIRMED_OK|CONFIRMED_VIOLATION") String complianceDecision,
                                @NotBlank @Size(max=2000) String complianceReason) {}
    public record ConfirmRequest(@Min(1) int expectedRevision) {}
    public record Review(int revision,String teacherId,Instant reviewedAt,List<LevelOverride> overrides,String complianceDecision,
                         String complianceReason,Result result,boolean confirmed) {}
    public record JobTarget(String id,Input input) {}
}
