package kr.lit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 교사 채점 화면(/teacher/grading)과 LIT AI 활용 평가 기준(100점 모델)의 API 모델. */
public final class AssessmentModels {
    private AssessmentModels() {}

    /** 각 평가 기준이 어떤 서버 지표로 AI 추천 점수를 받는지. MANUAL은 AI 추천 없이 교사만 채점합니다. */
    public enum Indicator { PURPOSE, SELF_DIRECTION, CRITICAL_REVIEW, CONTRIBUTION, TRANSPARENCY, MANUAL }
    public enum QuestionType { CONCEPT_UNDERSTANDING, RESEARCH, IDEA_EXPLORATION, ANSWER_DRAFTING, VERIFICATION, OTHER;
        public String label() { return switch(this) {
            case CONCEPT_UNDERSTANDING -> "개념 이해"; case RESEARCH -> "자료 탐색"; case IDEA_EXPLORATION -> "아이디어 구체화";
            case ANSWER_DRAFTING -> "답안 대리 작성 요청"; case VERIFICATION -> "검증 요청"; case OTHER -> "기타";
        }; }
    }
    public enum ComplianceResult { OK, CHECK, VIOLATION }
    public enum ComplianceDecision { CONFIRMED_OK, HOLD, CONFIRMED_VIOLATION }

    // ---- 루브릭 ----
    public record Level(int level,String label) {}
    public static final List<Level> LEVELS=List.of(new Level(0,"매우 못함"),new Level(1,"못함"),new Level(2,"보통"),new Level(3,"잘함"),new Level(4,"매우 잘함"));
    public record Criterion(int criterionId,String name,int maxScore,String description,List<String> levelDescriptions,Indicator indicator,String basis) {}
    public record Rubric(String evaluationId,List<Criterion> criteria,int totalScore,boolean custom,boolean locked,List<Level> levels,String model,boolean provisional) {}
    public record CriterionInput(@NotBlank @Size(max=100) String name,@Min(1) @Max(100) int maxScore,@Size(max=1000) String description,
                                 @Size(min=5,max=5) List<@NotNull @Size(max=500) String> levelDescriptions,@NotNull Indicator indicator) {
        public CriterionInput {if(indicator==null)indicator=Indicator.MANUAL;}
    }
    public record RubricInput(@NotEmpty @Size(max=10) List<@NotNull @Valid CriterionInput> criteria) {}

    // ---- 분석 ----
    public record Compliance(ComplianceResult result,List<String> reasons) {}
    public record Contribution(int direct,int aiReferenced,int aiUsed,String basis) {}
    public record Usage(QuestionType type,String label,int count,double share,String level) {}
    public record ClassifiedQuestion(long conversationId,String requestId,String questionId,QuestionType type,String label,String method) {}
    public record Paragraph(String id,String questionId,int questionPosition,String text,String source,double similarity,
                            double changedRatio,Long conversationId,boolean estimated,String declaredSource) {}
    public record Evidence(Long conversationId,String paragraphId,Long eventId,String note) {}
    public record IndicatorResult(Indicator indicator,Double value,Integer level,String reason,List<Evidence> evidence) {}
    public record Signals(int aiQuestions,int completedResponses,int pasteEvents,int aiCopyEvents,int warningEvents,
                          boolean disclosedTools,boolean disclosedUsedParts,boolean disclosedReason,boolean aiUsed) {}
    public record Analysis(String participantId,String status,String model,String aiPolicy,int aiLevel,Compliance compliance,
                           Contribution contribution,List<Usage> usage,List<ClassifiedQuestion> questions,List<Paragraph> paragraphs,
                           List<IndicatorResult> indicators,Signals signals,String summary,Instant analyzedAt,List<String> limitations) {}
    public record AnalysisRun(String evaluationId,int queued,String status) {}

    // ---- 점수 ----
    public record AiScore(int level,double score,String reason,List<Evidence> evidence) {}
    public record TeacherScore(int level,double score,String feedback,Instant updatedAt) {}
    public record CriterionScore(int criterionId,String name,int maxScore,Indicator indicator,AiScore ai,TeacherScore teacher,boolean adjusted) {}
    public record ComplianceReview(ComplianceResult ai,ComplianceDecision decision,String note) {}
    public record Scores(String participantId,List<CriterionScore> criteria,Double totalAi,Double totalTeacher,double totalCurrent,
                         String status,Double confirmedTotal,Instant confirmedAt,String analysisStatus,ComplianceReview compliance,int recommendedMaxScore,List<String> missingAiCriteria) {}
    public record ScoreInput(@Min(0) @Max(4) Integer level,@Size(max=2000) String feedback) {}
    public record ConfirmInput(Boolean acceptAiForMissing) {}
    public record ComplianceInput(@NotNull ComplianceDecision decision,@Size(max=1000) String note) {}
    public record ScoreChange(long id,int criterionId,Integer previousLevel,Integer level,Integer aiLevel,String teacherId,Instant changedAt) {}

    // ---- 제출 현황·답안·대화 ----
    public record SubmissionRow(String participantId,String name,String nickname,String status,boolean submitted,String submissionStatus,
                                Instant submittedAt,String submissionMode,int warningCount,String gradingStatus,Double totalScore,
                                Double totalAi,ComplianceResult compliance,String analysisStatus,int recommendedMaxScore) {}
    public record SubmissionCounts(long submitted,long warning,long writing,long confirmed) {}
    public record Submissions(String evaluationId,List<SubmissionRow> items,int page,int size,long totalElements,long totalPages,SubmissionCounts counts) {}
    public record AnswerQuestion(String questionId,int position,String title,String content,String answerHtml,int version,Instant updatedAt,
                                 String declaredSource,List<Paragraph> paragraphs) {}
    public record Answers(String participantId,String nickname,String status,Instant submittedAt,String submissionMode,List<AnswerQuestion> questions,boolean sourcesEstimated) {}
    public record Conversation(long conversationId,String requestId,String questionId,String question,String answer,String status,String errorCode,
                               Instant createdAt,Instant completedAt,QuestionType questionType,String questionTypeLabel) {}
}
