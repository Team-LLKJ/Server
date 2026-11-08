package kr.lit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import kr.lit.api.AssessmentModels.CriterionInput;

public final class WorkModels {
    private WorkModels() {}
    /** 프로젝트에서 사용하는 AI 허용5단계입니다. 기존 DISABLED/HINT_ONLY/FULL 값은 그대로 유지됩니다. */
    public enum AiPolicy {
        DISABLED(1), HINT_ONLY(2), EDIT_ASSIST(3), TASK_ASSIST(4), FULL(5);
        private final int level;
        AiPolicy(int level) { this.level=level; }
        public int level() { return level; }
        public static AiPolicy ofLevel(int level) {
            for(var p:values()) if(p.level==level) return p;
            throw new IllegalArgumentException("aiLevel must be 1..5");
        }
    }
    public enum AnswerSource { DIRECT, AI_REFERENCED, AI_USED }
    public record QuestionInput(@Min(1) int position, @NotBlank @Size(max=200) String title,
                                @NotBlank @Size(max=10000) String content,@Min(1) @Max(99999) Integer minChars,
                                @Min(1) @Max(99999) Integer maxChars) {
        public QuestionInput(int position,String title,String content) {this(position,title,content,null,null);}
    }
    public record Configure(AiPolicy aiPolicy,
                            @NotEmpty @Size(max=100) List<@NotNull @Valid QuestionInput> questions, @Size(max=10000) String guidelines, @Min(1) @Max(1440) Integer durationMinutes,
                            @Min(1) @Max(5) Integer aiLevel,
                            @Size(max=10) List<?> criteria,
                            com.fasterxml.jackson.databind.JsonNode antiCheat) {
        public Configure(AiPolicy aiPolicy,List<QuestionInput> questions,String guidelines,Integer durationMinutes,Integer aiLevel) {this(aiPolicy,questions,guidelines,durationMinutes,aiLevel,null,null);}
        public Configure(AiPolicy aiPolicy,List<QuestionInput> questions,String guidelines,Integer durationMinutes) { this(aiPolicy,questions,guidelines,durationMinutes,null); }
        public Configure(AiPolicy aiPolicy,List<QuestionInput> questions,String guidelines) { this(aiPolicy,questions,guidelines,null,null); }
        public Configure(AiPolicy aiPolicy,List<QuestionInput> questions) { this(aiPolicy,questions,null,null,null); }
        /** 둘 다 생략하면 HINT_ONLY이며, 둘 다 보내면 같은 단계여야 합니다. */
        public AiPolicy resolvedPolicy() {
            if(aiPolicy==null&&aiLevel==null) return AiPolicy.HINT_ONLY;
            if(aiPolicy!=null&&aiLevel!=null&&aiPolicy.level()!=aiLevel) return null;
            return aiPolicy!=null?aiPolicy:AiPolicy.ofLevel(aiLevel);
        }
    }
    public record SaveAnswer(@NotNull String content, @Min(0) int expectedVersion,
                             @NotNull AnswerSource source, @Size(max=64) String aiRequestId) {}
    /** 학생 화면이 신고하는 이벤트. 전체화면 이탈·개발자 도구 열림도 경고이며, 신고 자체가 부정행위 확정은 아닙니다. */
    public enum ClientEventType {
        WINDOW_LEAVE(true), SHORTCUT_CAPTURE(true), WINDOW_BLUR(true), CAPTURE_SHORTCUT(true), COPY_ATTEMPT(true), FULLSCREEN_EXIT(true), FULLSCREEN_LEAVE(true), DEVTOOLS_OPEN(true), FULLSCREEN(true), DEVTOOLS(true),
        PASTE(false), AI_ANSWER_COPY(false);
        private final boolean warning;
        ClientEventType(boolean warning) { this.warning=warning; }
        public boolean warning() { return warning; }
        public static java.util.List<String> warningNames() {
            return java.util.Arrays.stream(values()).filter(ClientEventType::warning).map(Enum::name).toList();
        }
        /** SQL IN 절용 상수 목록 (enum 이름만 사용하므로 주입 위험이 없습니다). */
        public static String warningSql() {
            return String.join(",",warningNames().stream().map(n->"'"+n+"'").toList());
        }
        public static String clientSql() {
            return String.join(",",java.util.Arrays.stream(values()).map(v->"'"+v.name()+"'").toList());
        }
    }
    public record ClientEvent(@NotNull ClientEventType type, @NotNull Instant occurredAt,
                              @Size(max=36) String questionId,
                              @Min(0) @Max(10000000) Integer length, @Min(1) Long conversationId,
                              @Min(0) @Max(10000000) Integer start,@Min(0) @Max(10000000) Integer end,@Min(0) Integer answerVersion) {
        public ClientEvent(ClientEventType type,Instant occurredAt,String questionId,Integer length,Long conversationId){this(type,occurredAt,questionId,length,conversationId,null,null,null);}
        public ClientEvent(ClientEventType type,Instant occurredAt,String questionId) { this(type,occurredAt,questionId,null,null); }
    }
    public record Disclosure(@NotNull @Size(max=500) String tools, @NotNull @Size(max=4000) String usedParts,
                             @NotNull @Size(max=4000) String reason) {}
    public record DisclosureView(String evaluationId, String participantId, String tools, String usedParts, String reason,
                                 Instant updatedAt, boolean editable, String status, String source, String errorCode,
                                 String failureReason, List<Long> conversationIds, List<DisclosureEvidence> evidence) {
        public DisclosureView(String evaluationId,String participantId,String tools,String usedParts,String reason,Instant updatedAt,boolean editable){this(evaluationId,participantId,tools,usedParts,reason,updatedAt,editable,"READY","STUDENT_REPORTED",null,null,List.of(),List.of());}
    }
    public record DisclosureEvidence(long conversationId,String questionId,String purpose,String messageRole,String quote,List<Integer> matchingAnswerParagraphs,int answerVersion) {}
    public record Ask(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestId,
                      @NotBlank @Size(max=4000) String text) {}
    public record Confirm(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String reviewToken) {}
    public record Question(String id, int position, String title, String content,Integer minChars,Integer maxChars) {
        public Question(String id,int position,String title,String content) {this(id,position,title,content,null,null);}
    }
    public record Answer(String questionId, String content, int version, Instant updatedAt) {}
    public record QuestionAnswer(Question question, Answer answer) {}
    public record Workspace(String evaluationId, String participantId, String status, boolean readOnly,
                            AiPolicy aiPolicy, List<QuestionAnswer> questions, String evaluationContent, String guidelines,
                            int aiLevel, String aiLevelDescription,List<?> criteria,
                            com.fasterxml.jackson.databind.JsonNode antiCheat,String assessmentId,String className,boolean policyAccepted,Instant policyAcceptedAt,String policyVersion) {}
    public record Configuration(String evaluationId, AiPolicy aiPolicy, List<Question> questions, String guidelines,int durationMinutes,
                                int aiLevel, String aiLevelDescription,List<?> criteria,
                            com.fasterxml.jackson.databind.JsonNode antiCheat,String assessmentId,String className) {}
    public record Turn(long id, String requestId, String questionId, String questionText, String answerText,
                       String state, String errorCode, String provider, String model, AiPolicy aiPolicy,
                       int answerVersion, Instant createdAt, Instant completedAt) {}
    public record Event(long id, String questionId, String type, com.fasterxml.jackson.databind.JsonNode details,
                        Instant createdAt) {}
    public record ReviewContent(String evaluationId, String participantId, AiPolicy aiPolicy,
                                List<QuestionAnswer> answers, List<Turn> conversations, List<Event> learningEvents,DisclosureView aiDisclosure) {
        public ReviewContent(String evaluationId,String participantId,AiPolicy aiPolicy,List<QuestionAnswer> answers,List<Turn> conversations,List<Event> learningEvents){this(evaluationId,participantId,aiPolicy,answers,conversations,learningEvents,null);}
    }
    public record AnswerValidationError(String questionId,int actualChars,int minChars,Integer maxChars) {}
    public record Review(String reviewToken, boolean canConfirm, List<String> blankQuestionIds, ReviewContent content,
                         List<AnswerValidationError> answerValidationErrors) {}
    public record Submission(String status, Instant submittedAt, com.fasterxml.jackson.databind.JsonNode snapshot,String submissionMode) {}
    public static String levelDescription(AiPolicy p) {
        return switch(p) {
            case DISABLED -> "Level 1 · AI 사용 금지 (사용 사실은 교사에게 표시)";
            case HINT_ONLY -> "Level 2 · 아이디어 생성·구조화만 허용, 최종 답안에 AI 문장 포함 불가";
            case EDIT_ASSIST -> "Level 3 · 학생 원고의 AI 편집 허용, 편집 결과 비교";
            case TASK_ASSIST -> "Level 4 · AI 과제 수행 허용, 학생이 생성물을 검토·평가";
            case FULL -> "Level 5 · AI 전면 활용·협업";
        };
    }
    public record Context(String evaluationId, String participantId, String state, Instant expiresAt) {}
    public record Reservation(Context context, Turn turn, Question question, Answer answer, List<Turn> history, boolean fresh) {}
}
