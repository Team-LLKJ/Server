package kr.lit.api;

import kr.lit.auth.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;

@Service
@Transactional
public class ProcessGradingService {
    private final EvaluationService evaluations;private final ProcessGradingRepository repository;private final ProcessGradingEngine engine;
    public ProcessGradingService(EvaluationService evaluations,ProcessGradingRepository repository,ProcessGradingEngine engine){this.evaluations=evaluations;this.repository=repository;this.engine=engine;}
    private ApiError.Failure fail(HttpStatus status,String code,String message){return new ApiError.Failure(status,code,message);}
    private String requireTeacher(String id){var user=AccountAccess.require(AuthModels.Role.TEACHER);evaluations.details(id);return user.userId();}
    private void requireParticipant(String id,String pid){if(!repository.existsParticipant(id,pid))throw fail(HttpStatus.NOT_FOUND,"PARTICIPANT_NOT_FOUND","해당 평가의 참가자가 아닙니다.");}
    public Map<String,Object> getRubric(String id) {
        requireTeacher(id);
        try(var stream=new org.springframework.core.io.ClassPathResource("grading/process-rubric-v3.md").getInputStream()) {
            return Map.of("rubricVersion",RUBRIC_VERSION,"provisional",true,"levels",List.of(new AssessmentModels.Level(1,"매우 못함"),new AssessmentModels.Level(2,"못함"),new AssessmentModels.Level(3,"보통"),new AssessmentModels.Level(4,"잘함"),new AssessmentModels.Level(5,"매우 잘함")),"scoreFormula","maxScore * (level - 1) / 4","criteria",Arrays.stream(Criterion.values()).map(c->Map.of("criterion",c.name(),"name",c.displayName(),"maxScore",c.maxScore())).toList(),"criteriaMarkdown",new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
    public Policy getPolicy(String id){requireTeacher(id);return repository.getPolicy(id).orElseThrow(()->fail(HttpStatus.NOT_FOUND,"PROCESS_POLICY_NOT_FOUND","평가 전 사전 정책이 필요합니다."));}
    public Policy savePolicy(String id,PolicyRequest request) {
        requireTeacher(id);
        if(!repository.getEvaluationState(id).equals("WAITING"))throw fail(HttpStatus.CONFLICT,"PROCESS_POLICY_LOCKED","평가 시작 후에는 사전 정책을 바꿀 수 없습니다.");
        var questions=repository.getQuestionIds(id);var ids=request.questionWeights().stream().map(QuestionWeight::questionId).toList();
        BigDecimal sum=request.questionWeights().stream().map(QuestionWeight::weight).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(questions.isEmpty()||ids.size()!=new HashSet<>(ids).size()||!new HashSet<>(ids).equals(new HashSet<>(questions))||sum.compareTo(BigDecimal.valueOf(100))!=0)
            throw fail(HttpStatus.BAD_REQUEST,"INVALID_QUESTION_WEIGHTS","모든 문항을 중복 없이 포함하고 배점 비율 합계가100이어야 합니다.");
        if(request.excludedCriteria()!=null&&new HashSet<>(request.excludedCriteria()).size()!=request.excludedCriteria().size())throw fail(HttpStatus.BAD_REQUEST,"INVALID_EXCLUDED_CRITERIA","평가 제외 항목은 중복할 수 없습니다.");
        int current=repository.getPolicy(id).map(Policy::version).orElse(0);
        if(current!=request.expectedVersion())throw fail(HttpStatus.CONFLICT,"PROCESS_POLICY_VERSION_CONFLICT","최신 사전 정책을 확인해주세요.");
        var policy=new Policy(current+1,RUBRIC_VERSION,request.taskGoal(),request.allowedRules(),request.noAiPolicy(),List.copyOf(request.questionWeights()),request.excludedCriteria());
        repository.savePolicy(id,policy);return policy;
    }
    public List<Job> getRuns(String id,String pid){requireTeacher(id);requireParticipant(id,pid);return repository.getJobs(id,pid);}
    public Job getRun(String id,String pid,String jobId){return getRuns(id,pid).stream().filter(j->j.id().equals(jobId)).findFirst().orElseThrow(()->fail(HttpStatus.NOT_FOUND,"PROCESS_RUN_NOT_FOUND","분석 버전을 찾을 수 없습니다."));}
    public Job queueAnalysis(String id,String pid) {
        requireTeacher(id);requireParticipant(id,pid);
        if(!repository.hasSubmission(id,pid))throw fail(HttpStatus.CONFLICT,"SUBMISSION_REQUIRED","제출 후 분석할 수 있습니다.");
        if(repository.getJobs(id,pid).stream().anyMatch(j->Set.of("PENDING","RUNNING").contains(j.status())))
            throw fail(HttpStatus.CONFLICT,"PROCESS_ANALYSIS_PENDING","대기·진행 중인 분석이 있습니다.");
        String job=repository.queueSubmission(id,pid);return getRun(id,pid,job);
    }
    public Input getEvidence(String id,String pid,String jobId){getRun(id,pid,jobId);return repository.getInput(jobId);}
    public Review getReview(String id,String pid,String jobId){getRun(id,pid,jobId);return repository.getReview(jobId).orElseThrow(()->fail(HttpStatus.NOT_FOUND,"PROCESS_REVIEW_NOT_FOUND","교사 검토가 아직 없습니다."));}
    public List<Review> getReviewHistory(String id,String pid,String jobId){getRun(id,pid,jobId);return repository.getReviewHistory(jobId);}
    public Review saveReview(String id,String pid,String jobId,ReviewRequest request) {
        String teacher=requireTeacher(id);var job=getRun(id,pid,jobId);repository.lockJob(jobId);
        if(job.result()==null||!Set.of("DONE","HOLD").contains(job.status()))throw fail(HttpStatus.CONFLICT,"PROCESS_RESULT_REQUIRED","검토 가능한 분석 결과가 필요합니다.");
        int revision=repository.getReview(jobId).map(Review::revision).orElse(0);
        if(request.expectedRevision()!=revision)throw fail(HttpStatus.CONFLICT,"PROCESS_REVIEW_VERSION_CONFLICT","최신 교사 검토를 확인해주세요.");
        Result result;
        try{result=engine.applyReview(repository.getInput(jobId),job.result(),request.overrides());}catch(IllegalArgumentException ex){throw fail(HttpStatus.BAD_REQUEST,"INVALID_PROCESS_REVIEW","문항·영역·수준·보정 사유를 확인해주세요.");}
        var review=new Review(revision+1,teacher,Instant.now(),request.overrides(),request.complianceDecision(),request.complianceReason(),result,false);
        repository.saveReview(jobId,review);return review;
    }
    public Review confirmReview(String id,String pid,String jobId,ConfirmRequest request) {
        String teacher=requireTeacher(id);var job=getRun(id,pid,jobId);repository.lockJob(jobId);
        if(!repository.getJobs(id,pid).get(0).id().equals(jobId)||!repository.getCurrentSubmissionHash(id,pid).equals(repository.getInput(jobId).submissionHash()))
            throw fail(HttpStatus.CONFLICT,"PROCESS_RESULT_STALE","재분석 또는 제출본 변경 후 최신 결과를 검토해주세요.");
        var review=repository.getReview(jobId).orElseThrow(()->fail(HttpStatus.CONFLICT,"PROCESS_REVIEW_REQUIRED","먼저 교사 검토를 저장해주세요."));
        if(review.revision()!=request.expectedRevision())throw fail(HttpStatus.CONFLICT,"PROCESS_REVIEW_VERSION_CONFLICT","최신 교사 검토를 확인해주세요.");
        if(review.result().total()==null||review.complianceDecision().equals("HOLD"))throw fail(HttpStatus.CONFLICT,"PROCESS_CONFIRMATION_HELD","판단 보류 항목과 규정 준수 검토를 먼저 완료해주세요.");
        if(review.confirmed())return review;
        var confirmed=new Review(review.revision()+1,teacher,Instant.now(),review.overrides(),review.complianceDecision(),review.complianceReason(),review.result(),true);
        repository.saveReview(jobId,confirmed);return confirmed;
    }
}
