package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import static kr.lit.api.WorkModels.*;
import static kr.lit.api.AssessmentModels.*;

/** 수행평가 틀 API. 기존 AssessmentModels는 반별 채점 API 계약으로 유지합니다. */
public final class TemplateModels {
    private TemplateModels() {}
    public record Input(@NotBlank @Size(max=100) String title,@Size(max=10000) String guidelines,
                        @NotNull AiPolicy aiPolicy,@Size(max=10) List<?> criteria,
                        JsonNode antiCheat,@NotEmpty @Size(max=100) List<@NotNull @Valid QuestionInput> questions,
                        @Min(1) @Max(1440) Integer durationMinutes,@Min(1) Integer revision) {
        public Input(String title,String guidelines,AiPolicy aiPolicy,List<?> criteria,JsonNode antiCheat,List<QuestionInput> questions,Integer durationMinutes) {
            this(title,guidelines,aiPolicy,criteria,antiCheat,questions,durationMinutes,null);
        }
    }
    public record ClassInput(@NotBlank @Size(max=60) String className) {}
    public record ShareInput(@NotBlank @Email @Size(max=254) String email,String role) {
        public ShareInput(String email) {this(email,"EDITOR");}
        public ShareInput {if(email!=null)email=email.strip();if(role==null)role="EDITOR";}
    }
    public record Owner(String teacherId,String name) {}
    public record Collaborator(String teacherId,String name,String email,Instant sharedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty public String role() {return "EDITOR";}
        @com.fasterxml.jackson.annotation.JsonProperty public String userId() {return teacherId;}
    }
    public record StartInput(@Min(1) @Max(1440) int expiresInMinutes) {}
    public record ClassView(String classId,String evaluationId,String assessmentId,String className,String state,String evaluationState,
                            String pin,String joinUrl,String qrUrl,Instant expiresAt,Instant endsAt,
                            int participantCount,int submittedCount,int templateRevision) {}
    public record View(String id,String title,String guidelines,AiPolicy aiPolicy,List<?> criteria,
                       JsonNode antiCheat,List<QuestionInput> questions,int durationMinutes,int revision,
                       int questionCount,int classCount,List<ClassView> classes,Instant createdAt,Instant updatedAt,Owner owner,String role,int collaboratorCount) {
        @com.fasterxml.jackson.annotation.JsonProperty public String myRole() {return role;}
        @com.fasterxml.jackson.annotation.JsonProperty public boolean shared() {return collaboratorCount>0;}
    }
    public record Summary(String id,String title,int questionCount,int classCount,List<ClassView> classes,
                          int revision,Instant updatedAt,Owner owner,String role,AiPolicy aiPolicy,String guidelinesSummary,int collaboratorCount) {
        @com.fasterxml.jackson.annotation.JsonProperty public String myRole() {return role;}
        @com.fasterxml.jackson.annotation.JsonProperty public boolean shared() {return collaboratorCount>0;}
    }
    public record Page(List<Summary> items,int page,int size,long totalElements,long totalPages,String scope) {}
}
