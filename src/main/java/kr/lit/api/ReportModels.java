package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ReportModels {
 private ReportModels() {}
 public enum Criterion { PURPOSE_CLARITY, QUESTION_SPECIFICITY, VERIFICATION, REVISION, POLICY_COMPLIANCE }
 public enum Compliance { NOT_REVIEWED, COMPLIANT, NEEDS_REVIEW }
 public record Rating(@NotNull Criterion criterion,@NotNull @Min(0) @Max(4) Integer score,
                      @NotBlank @Size(max=2000) String evidence) {}
 public record ReviewInput(@Min(0) int expectedVersion,@NotNull Compliance compliance,
                           @NotNull @Size(min=5,max=5) List<@NotNull @Valid Rating> ratings,
                           @NotNull @Size(max=5000) String comment) {}
 public record TeacherReview(int version,Compliance compliance,List<Rating> ratings,String comment,
                             String teacherId,Instant reviewedAt,int maxScore) {
  public TeacherReview {if(maxScore==0)maxScore=4;}
  public TeacherReview(int version,Compliance compliance,List<Rating> ratings,String comment,String teacherId,Instant reviewedAt){this(version,compliance,ratings,comment,teacherId,reviewedAt,4);}
 }
 public record Student(String participantId,String nickname,String presence,String submissionStatus,
                       String workStatus,boolean warning,Instant submittedAt,Integer reportVersion,String reviewStatus,String status,boolean activityWarning,int warningCount,Instant lastEventAt) {}
 public record Counts(long submitted,long warning,long writing) {}
 public record Students(List<Student> items,int page,int size,long totalElements,long totalPages,Counts counts,List<Student> students) {}
 public record ScoreInput(@com.fasterxml.jackson.annotation.JsonProperty(required=true) @Min(0) @Max(20) Integer score,
                          @Min(0) Integer expectedVersion) {}
 public record ReviewChange(long id,String criterionId,Integer previousScore,Integer score,String teacherId,Instant changedAt,int reviewVersion,int previousMaxScore,int maxScore) {}
 public record CriterionView(String id,String criterion,Integer score,int maxScore,String feedback,List<String> evidence,boolean adjusted) {}
 public record Rubric(int version,String analysisStatus,String analysisMethod,String rubricStatus,List<CriterionView> criteria,List<CriterionView> analysis,List<ReviewChange> adjustments) {}
 public record GradingQuestion(String questionId,int position,String title,String content,com.fasterxml.jackson.databind.JsonNode answer) {}
 public record GradingAnswers(String participantId,String nickname,Instant submittedAt,String status,List<GradingQuestion> questions) {}
 public record Message(String id,String role,String content,String questionId,Instant createdAt,String status,String errorCode) {}
 public record Usage(String name,double percentage,String level,String colorKey) {}
 public record ActivityWarning(long eventId,String type,String questionId,Instant occurredAt,Instant receivedAt,boolean clientReported) {}
 public record Activity(Double studentContribution,List<Usage> usage,List<ActivityWarning> warnings,long warningsTotal,boolean hasMoreWarnings,String summary,String analysisMethod) {}
 public record Classification(String requestId,String questionId,List<String> purposes,List<String> stages,
                              String evidence,String confidence) {}
 public record Analysis(String method,String status,List<Classification> classifications,List<String> missingData,
                        List<String> limitations) {}
 public record Detail(Student student,JsonNode work,Analysis analysis,TeacherReview teacherReview) {}
 public record GradingCriterion(int criterionId,String name,int maxScore,Double score,boolean teacherScored,String feedback,String aiReason) {}
 public record GradingSnapshot(String status,List<GradingCriterion> criteria) {}
 public record ReportContent(String evaluationId,String title,String participantId,String nickname,Instant submittedAt,
                             String submissionHash,JsonNode submission,Analysis analysis,TeacherReview teacherReview,
                             String rubricStatus,List<String> limitations,GradingSnapshot grading) {
  public ReportContent(String evaluationId,String title,String participantId,String nickname,Instant submittedAt,String submissionHash,JsonNode submission,Analysis analysis,TeacherReview teacherReview,String rubricStatus,List<String> limitations){this(evaluationId,title,participantId,nickname,submittedAt,submissionHash,submission,analysis,teacherReview,rubricStatus,limitations,null);}
 }
 public record UsageReport(String id,int version,Instant createdAt,ReportContent content) {}
 public record ReportVersion(String id,int version,Instant createdAt) {}
}
