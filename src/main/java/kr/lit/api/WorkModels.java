package kr.lit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class WorkModels {
    private WorkModels() {}
    public enum AiPolicy { DISABLED, HINT_ONLY, FULL }
    public enum AnswerSource { DIRECT, AI_REFERENCED, AI_USED }
    public record QuestionInput(@Min(1) int position, @NotBlank @Size(max=200) String title,
                                @NotBlank @Size(max=10000) String content) {}
    public record Configure(@NotNull AiPolicy aiPolicy,
                            @NotEmpty @Size(max=100) List<@NotNull @Valid QuestionInput> questions, @Size(max=10000) String guidelines) {
        public Configure(AiPolicy aiPolicy,List<QuestionInput> questions) { this(aiPolicy,questions,null); }
    }
    public record SaveAnswer(@NotNull String content, @Min(0) int expectedVersion,
                             @NotNull AnswerSource source, @Size(max=64) String aiRequestId) {}
    public enum ClientEventType { WINDOW_LEAVE, SHORTCUT_CAPTURE }
    public record ClientEvent(@NotNull ClientEventType type, @NotNull Instant occurredAt,
                              @Size(max=36) String questionId) {}
    public record Ask(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestId,
                      @NotBlank @Size(max=4000) String text) {}
    public record Confirm(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String reviewToken) {}
    public record Question(String id, int position, String title, String content) {}
    public record Answer(String questionId, String content, int version, Instant updatedAt) {}
    public record QuestionAnswer(Question question, Answer answer) {}
    public record Workspace(String evaluationId, String participantId, String status, boolean readOnly,
                            AiPolicy aiPolicy, List<QuestionAnswer> questions, String evaluationContent, String guidelines) {}
    public record Configuration(String evaluationId, AiPolicy aiPolicy, List<Question> questions, String guidelines) {}
    public record Turn(long id, String requestId, String questionId, String questionText, String answerText,
                       String state, String errorCode, String provider, String model, AiPolicy aiPolicy,
                       int answerVersion, Instant createdAt, Instant completedAt) {}
    public record Event(long id, String questionId, String type, com.fasterxml.jackson.databind.JsonNode details,
                        Instant createdAt) {}
    public record ReviewContent(String evaluationId, String participantId, AiPolicy aiPolicy,
                                List<QuestionAnswer> answers, List<Turn> conversations, List<Event> learningEvents) {}
    public record Review(String reviewToken, boolean canConfirm, List<String> blankQuestionIds, ReviewContent content) {}
    public record Submission(String status, Instant submittedAt, com.fasterxml.jackson.databind.JsonNode snapshot) {}
    public record Context(String evaluationId, String participantId, String state, Instant expiresAt) {}
    public record Reservation(Context context, Turn turn, Question question, Answer answer, List<Turn> history, boolean fresh) {}
}
