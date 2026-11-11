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
    public enum QuestionType { CONCEPT_UNDERSTANDING, RESEARCH, IDEA_EXPLORATION, ANSWER_DRAFTING, VERIFICATION, EDIT, OTHER;
        public String label() { return switch(this) {
            case CONCEPT_UNDERSTANDING -> "개념 이해"; case RESEARCH -> "자료 탐색"; case IDEA_EXPLORATION -> "아이디어 구체화";
            case ANSWER_DRAFTING -> "답안 대리 작성 요청"; case VERIFICATION -> "검증 요청"; case EDIT -> "표현 수정 요청"; case OTHER -> "기타";
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
    public record Reason(String type,String text,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Double scoreDelta) {}
    public record Segment(String questionId,int start,int end,String type,double similarity,double effectiveSimilarity,boolean estimated,int answerVersion) {}
    public record AnswerText(String questionId,String text,int answerVersion) {}
    public record PasteObservation(long eventId,String questionId,String occurredAt,Integer length,Integer start,Integer end,Integer answerVersion,boolean matchesFinalAnswer,boolean clientReported) {}
    public record Contribution(int direct,int aiReferenced,int aiUsed,String basis,List<Segment> segments) {
        public Contribution(int direct,int aiReferenced,int aiUsed,String basis){this(direct,aiReferenced,aiUsed,basis,List.of());}
    }
    public record Usage(QuestionType type,String label,int count,double share,String level) {}
    public record ClassifiedQuestion(long conversationId,String requestId,String questionId,QuestionType type,String label,String method) {}
    public record Paragraph(String id,String questionId,int questionPosition,String text,String source,double similarity,
                            double changedRatio,Long conversationId,boolean estimated,String declaredSource) {}
    /**
     * 답안 출처 판정 V2의 문장별 결과. source는 DIRECT 또는 AI_REFERENCED, reason은 SPAN_MATCH(최장 일치 구간)·GRAM_OVERLAP(3-gram 포함률)·null(DIRECT).
     * start/end는 answerTexts[].text 기준 UTF-16 위치(end 미포함)입니다.
     */
    public record Sentence(String id,String questionId,String paragraphId,String text,String source,String reason,int longestSpan,
                           double similarity,Long conversationId,int start,int end,int answerVersion,boolean estimated,
                           boolean needsReview,SemanticVerdict semantic,String teacherLabel) {
        /** LLM이 고른 가장 가까운 AI 문장(없으면 null). */
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String closestAiSentence(){return semantic==null?null:semantic.closestCandidateText();}
        /** 교사가 확정한 출처(AI|DIRECT, 확정 전 null). */
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String teacherSource(){return teacherLabel;}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Integer haikuLevel(){return semantic==null?null:semantic.haikuLevel();}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Integer geminiLevel(){return semantic==null?null:semantic.geminiLevel();}
        public Sentence(String id,String questionId,String paragraphId,String text,String source,String reason,int longestSpan,
                        double similarity,Long conversationId,int start,int end,int answerVersion,boolean estimated){
            this(id,questionId,paragraphId,text,source,reason,longestSpan,similarity,conversationId,start,end,answerVersion,estimated,false,null,null);
        }
    }
    /** 교사의 문장 판정 수정(3단계 보정 자료). AI 또는 DIRECT. */
    public record SentenceLabelInput(@Pattern(regexp="AI|DIRECT") String label,@Pattern(regexp="AI|DIRECT") String source) {
        public SentenceLabelInput(String label){this(label,null);}
        /** label(이전 이름)과 source(문서 이름) 중 들어온 값. */
        public String value(){return source!=null?source:label;}
    }
    /**
     * 2단계 LLM 판정 결과(문장 하나). haikuLevel·geminiLevel은 0~3 단계(3: 거의 그대로 따라감, 2: 핵심 주장 같음,
     * 1: 일반적 내용·자기 경험 중심, 0: 다름). geminiLevel은 Haiku 단계 2라서 Gemini에 보낸 문장만 있고, 보내지 않았거나 실패하면 null입니다.
     * ai=true면 reason=SEMANTIC으로 AI 비중에 들어가고, needsReview=true면 비율에서는 직접으로 계산하고 교사 확인으로 둡니다.
     */
    public record SemanticVerdict(Integer haikuLevel,Integer geminiLevel,String closestCandidateId,String closestCandidateText,
                                  Long conversationId,boolean ai,boolean needsReview) {}
    /** analyze()에 넘기는 저장된 2단계 판정. key는 AssessmentAnalyzer.sentenceKey(questionId,문장). */
    public record SemanticInput(String status,java.util.Map<String,SemanticVerdict> verdicts) {
        public static final SemanticInput DISABLED=new SemanticInput("DISABLED",java.util.Map.of());
        public SemanticInput {verdicts=verdicts==null?java.util.Map.of():java.util.Map.copyOf(verdicts);}
    }
    public record Evidence(Long conversationId,String paragraphId,Long eventId,String note,String type,String messageRole,String quote,String reason) {
        public Evidence(Long conversationId,String paragraphId,Long eventId,String note){this(conversationId,paragraphId,eventId,note,null,null,null,null);}
    }
    public record IndicatorResult(Indicator indicator,Double value,Integer level,String reason,List<Evidence> evidence,List<Reason> reasons) {
        public IndicatorResult(Indicator indicator,Double value,Integer level,String reason,List<Evidence> evidence){this(indicator,value,level,reason,evidence,List.of());}
    }
    public record Signals(int aiQuestions,int completedResponses,int pasteEvents,int aiCopyEvents,int warningEvents,
                          boolean disclosedTools,boolean disclosedUsedParts,boolean disclosedReason,boolean aiUsed) {}
    public record Analysis(String participantId,String status,String model,String aiPolicy,int aiLevel,Compliance compliance,
                           Contribution contribution,List<Usage> usage,List<ClassifiedQuestion> questions,List<Paragraph> paragraphs,
                           List<IndicatorResult> indicators,Signals signals,String summary,Instant analyzedAt,List<String> limitations,List<AnswerText> answerTexts,List<PasteObservation> pasteEvents,java.util.Map<String,Object> definition,List<Sentence> sentences,String semanticStatus) {
        /** 교사 확인이 필요한 문장 수. */
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public int reviewCount(){return sentences==null?0:(int)sentences.stream().filter(Sentence::needsReview).count();}
        public Analysis(String participantId,String status,String model,String aiPolicy,int aiLevel,Compliance compliance,Contribution contribution,List<Usage> usage,List<ClassifiedQuestion> questions,List<Paragraph> paragraphs,List<IndicatorResult> indicators,Signals signals,String summary,Instant analyzedAt,List<String> limitations){this(participantId,status,model,aiPolicy,aiLevel,compliance,contribution,usage,questions,paragraphs,indicators,signals,summary,analyzedAt,limitations,List.of(),List.of(),java.util.Map.of(),List.of(),null);}
    }
    public record AnalysisRun(String evaluationId,int queued,String status) {}

    // ---- 점수 ----
    public record AiScore(int level,double score,String reason,List<Evidence> evidence,List<Reason> reasons) {}
    public record TeacherScore(int level,double score,String feedback,Instant updatedAt) {}
    /**
     * ③ 루브릭 LLM 판정(항목 하나). status: OK | NA(해당 없음, 총점에서 뺌) | HOLD(보류). level·score는 확정됐을 때만 값이 있고,
     * needsReview=true면 교사 직접 입력이 필요합니다(reviewReason: DISAGREEMENT | INSUFFICIENT_DATA). level은 1~5단계(teacherLevel도 같은 척도).
     * votes는 Gemini 회차별 답(무효 회차는 null), haikuLevel은 엇갈렸을 때 Haiku 답, runs는 회차별 원래 답(참고·보정용)입니다.
     */
    public record RubricJudgmentView(int criterionId,String status,Integer level,java.math.BigDecimal score,List<Object> votes,Object haikuLevel,
                                     boolean needsReview,String reviewReason,List<RubricJudge.Evidence> evidence,String met,String blocked,
                                     Integer teacherLevel,String method,List<RubricJudge.Run> runs) {}
    public record CriterionScore(int criterionId,String name,int maxScore,Indicator indicator,AiScore ai,TeacherScore teacher,boolean adjusted,
                                 boolean notApplicable,RubricJudgmentView judgment) {
        public CriterionScore(int criterionId,String name,int maxScore,Indicator indicator,AiScore ai,TeacherScore teacher,boolean adjusted){
            this(criterionId,name,maxScore,indicator,ai,teacher,adjusted,false,null);
        }
    }
    public record ComplianceReview(ComplianceResult ai,ComplianceDecision decision,String note) {}
    /** maxTotal: NA(해당 없음) 항목을 뺀 만점. gradingStatus: QUEUED | GRADING | DONE | FAILED(제출 전 null). reviewCount: 교사 확인이 필요한 항목 수. */
    public record Scores(String participantId,List<CriterionScore> criteria,Double totalAi,Double totalTeacher,double totalCurrent,
                         String status,Double confirmedTotal,Instant confirmedAt,String analysisStatus,ComplianceReview compliance,int recommendedMaxScore,List<String> missingAiCriteria,String summary,
                         int maxTotal,String gradingStatus,int reviewCount) {}
    /** 교사의 루브릭 단계 확정(1~5단계). */
    public record LevelInput(@jakarta.validation.constraints.NotNull @Min(1) @Max(5) Integer level,@Size(max=2000) String feedback) {}
    public record ScoreInput(@Min(0) @Max(4) Integer level,@Size(max=2000) String feedback) {}
    public record ConfirmInput(Boolean acceptAiForMissing) {}
    public record ComplianceInput(@NotNull ComplianceDecision decision,@Size(max=1000) String note) {}
    public record ScoreChange(long id,int criterionId,Integer previousLevel,Integer level,Integer aiLevel,String teacherId,Instant changedAt,String previousFeedback,String feedback) {}

    // ---- 제출 현황·답안·대화 ----
    public record SubmissionRow(String participantId,String name,String nickname,String status,boolean submitted,String submissionStatus,
                                Instant submittedAt,String submissionMode,int warningCount,String gradingStatus,Double totalScore,
                                Double totalAi,ComplianceResult compliance,String analysisStatus,int recommendedMaxScore,Instant lastEventAt,Instant lastWarningAt,boolean expelled,Instant expelledAt) {}
    public record SubmissionCounts(long submitted,long warning,long writing,long confirmed) {}
    public record Submissions(String evaluationId,List<SubmissionRow> items,int page,int size,long totalElements,long totalPages,SubmissionCounts counts) {}
    public record AnswerQuestion(String questionId,int position,String title,String content,String answerHtml,int version,Instant updatedAt,
                                 String declaredSource,List<Paragraph> paragraphs,Integer minChars,Integer maxChars) {}
    public record Answers(String participantId,String nickname,String status,Instant submittedAt,String submissionMode,List<AnswerQuestion> questions,boolean sourcesEstimated) {}
    public record Conversation(long conversationId,String requestId,String questionId,String question,String answer,String status,String errorCode,
                               Instant createdAt,Instant completedAt,QuestionType questionType,String questionTypeLabel) {}
}
