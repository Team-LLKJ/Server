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
    public static final String RUBRIC_VERSION="LIT_PROCESS_RUBRIC_V2";
    public static final String GLOBAL="GLOBAL";
    public enum Criterion { PURPOSE, SELF_DIRECTION, CRITICAL_REVIEW, CONTRIBUTION, TRANSPARENCY;
        public String displayName(){return switch(this){case PURPOSE->"AI 활용 목적의 적절성";case SELF_DIRECTION->"자기 주도적 사고";case CRITICAL_REVIEW->"AI 답변의 비판적 검토";case CONTRIBUTION->"최종 답안에 대한 학생의 기여";case TRANSPARENCY->"AI 활용 과정의 투명성";};}
        public int maxScore(){return switch(this){case PURPOSE,TRANSPARENCY->15;case SELF_DIRECTION,CONTRIBUTION->25;case CRITICAL_REVIEW->20;};}
    }
    public enum NoAiPolicy { HOLD, NOT_APPLICABLE, USE_RECORDED_EVIDENCE }
    public record QuestionWeight(@NotBlank String questionId,@NotNull @DecimalMin("0.0001") @DecimalMax("100") @Digits(integer=3,fraction=4) BigDecimal weight) {}
    public record PolicyRequest(@Min(0) int expectedVersion,@NotBlank @Size(max=10000) String taskGoal,
            @NotBlank @Size(max=10000) String allowedRules,@NotNull NoAiPolicy noAiPolicy,
            @NotEmpty @Size(max=100) List<@NotNull @Valid QuestionWeight> questionWeights) {}
    public record Policy(int version,String rubricVersion,String taskGoal,String allowedRules,NoAiPolicy noAiPolicy,List<QuestionWeight> questionWeights) {}
    public record Source(String id,String questionId,String kind,String text,String occurredAt,Integer answerVersion) {
        public Source(String id,String questionId,String kind,String text){this(id,questionId,kind,text,null,null);}
    }
    public record Input(String evaluationId,String participantId,String submissionHash,String title,String aiPolicy,
                        Policy policy,List<String> questionIds,List<Source> sources,List<String> limitations) {}
    public record Citation(String sourceId,String quote) {}
    public record Condition(String key,List<Citation> evidence) {}
    public record Recommendation(String questionId,Criterion criterion,Integer level,String reason,String unmetNextLevel,
                                 List<Condition> conditions,List<Citation> counterEvidence,List<String> limitations) {}
    public record Compliance(String status,String reason,List<Citation> evidence,List<String> limitations) {}
    public record AiOutput(Compliance compliance,List<Recommendation> recommendations) {}
    public record Cell(String questionId,Criterion criterion,Integer level,BigDecimal score,String status,String reason,
                       String unmetNextLevel,List<Condition> conditions,List<Citation> counterEvidence,List<String> limitations) {
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public int maxScore(){return criterion.maxScore();}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String criterionName(){return criterion.displayName();}
    }
    public record Result(String status,Compliance compliance,List<Cell> cells,BigDecimal total,List<String> limitations) {}
    public record Job(String id,String evaluationId,String participantId,int version,String status,String inputHash,
                      String rubricVersion,String model,Instant createdAt,Instant completedAt,Result result,String errorCode) {}
    public record LevelOverride(@NotBlank String questionId,@NotNull Criterion criterion,@NotNull @Min(0) @Max(5) Integer level,
                           @NotBlank @Size(max=2000) String reason) {}
    public record ReviewRequest(@Min(0) int expectedRevision,@NotNull @Size(max=401) List<@NotNull @Valid LevelOverride> overrides,
                                @NotBlank @Pattern(regexp="HOLD|CONFIRMED_OK|CONFIRMED_VIOLATION") String complianceDecision,
                                @NotBlank @Size(max=2000) String complianceReason) {}
    public record ConfirmRequest(@Min(1) int expectedRevision) {}
    public record Review(int revision,String teacherId,Instant reviewedAt,List<LevelOverride> overrides,String complianceDecision,
                         String complianceReason,Result result,boolean confirmed) {}
    public record JobTarget(String id,Input input) {}
}
