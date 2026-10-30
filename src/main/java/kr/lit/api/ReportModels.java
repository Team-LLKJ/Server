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
                             String teacherId,Instant reviewedAt) {}
 public record Student(String participantId,String nickname,String presence,String submissionStatus,
                       String workStatus,boolean warning,Instant submittedAt,Integer reportVersion,String reviewStatus) {}
 public record Students(List<Student> items,int page,int size,long totalElements,long totalPages) {}
 public record Classification(String requestId,String questionId,List<String> purposes,List<String> stages,
                              String evidence,String confidence) {}
 public record Analysis(String method,String status,List<Classification> classifications,List<String> missingData,
                        List<String> limitations) {}
 public record Detail(Student student,JsonNode work,Analysis analysis,TeacherReview teacherReview) {}
 public record ReportContent(String evaluationId,String title,String participantId,String nickname,Instant submittedAt,
                             String submissionHash,JsonNode submission,Analysis analysis,TeacherReview teacherReview,
                             String rubricStatus,List<String> limitations) {}
 public record UsageReport(String id,int version,Instant createdAt,ReportContent content) {}
 public record ReportVersion(String id,int version,Instant createdAt) {}
}
