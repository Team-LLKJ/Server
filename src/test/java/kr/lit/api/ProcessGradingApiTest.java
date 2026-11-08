package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProcessGradingApiTest extends AuthTestSupport {
    @Autowired ProcessGradingService service;
    @Autowired ProcessGradingRepository repository;
    @Autowired ProcessGradingWorker worker;
    @Autowired AiService aiService;
    @MockitoBean ProcessGradingAiClient ai;
    record Fixture(String id,String pid,String token,String question) {}
    Fixture fixture(boolean configured,boolean aiUsed) {
        as(teacher);var e=evaluations.create("근거 기반 평가",60);var config=work.configure(e.id(),new Configure(AiPolicy.TASK_ASSIST,List.of(new QuestionInput(1,"문항","학교 잔반 문제를 해결하세요."))));
        String q=config.questions().get(0).id();
        if(configured)service.savePolicy(e.id(),new PolicyRequest(0,"학교 잔반 해결","답안 생성 허용",NoAiPolicy.HOLD,List.of(new QuestionWeight(q,new BigDecimal("100")))));
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        work.save(e.id(),p.participantToken(),q,new SaveAnswer("학생의 조건과 근거를 반영한 최종 답안입니다.",0,AnswerSource.DIRECT,null));
        if(aiUsed)aiService.ask(e.id(),p.participantToken(),q,new Ask("grading-test","학교의 조건을 반영하는 방법을 비교해줘"));
        work.saveDisclosure(e.id(),p.participantToken(),new Disclosure("LIT AI","최종 답안의 실행 방안","우리 학교 인력 조건 때문에 선택했습니다."));
        var preview=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),preview.reviewToken());
        return new Fixture(e.id(),p.participantId(),p.participantToken(),q);
    }
    String root(Fixture f){return "/api/teacher/evaluations/"+f.id()+"/process-grading/participants/"+f.pid()+"/runs";}
    Job latest(Fixture f){as(teacher);return service.getRuns(f.id(),f.pid()).get(0);}
    AiOutput validOutput(Input input) {
        var recommendations=new ArrayList<Recommendation>();var engine=new ProcessGradingEngine();
        for(String q:input.questionIds())for(var c:Criterion.values())if(c!=Criterion.TRANSPARENCY) {
            var src=input.sources().stream().filter(s->s.kind().equals("FINAL_ANSWER")&&s.questionId().equals(q)).findFirst().orElseThrow();
            recommendations.add(new Recommendation(q,c,4,"과제 조건에 연결함","발전·반영의 추가 근거 부족",List.of(new Condition(engine.requiredConditions(c,4).get(0),List.of(new Citation(src.id(),src.text())))),List.of(),List.of()));
        }
        var disclosure=input.sources().stream().filter(s->s.kind().equals("FINAL_ANSWER")).findFirst().orElseThrow();
        recommendations.add(new Recommendation(GLOBAL,Criterion.TRANSPARENCY,4,"활용 공개","추가 이유 부족",List.of(new Condition("TRANSPARENCY_L4",List.of(new Citation(disclosure.id(),disclosure.text())))),List.of(),List.of()));
        return new AiOutput(new Compliance("CHECK","교사 확인",List.of(new Citation(disclosure.id(),disclosure.text())),List.of()),recommendations);
    }
    @Test void submissionFreezesInputsAndWorkerPublishesSeventyFiveWithVersionedReview() throws Exception {
        var f=fixture(true,true);var job=latest(f);var input=repository.getInput(job.id());
        assertThat(input.policy().version()).isEqualTo(1);assertThat(input.sources()).anyMatch(s->s.kind().equals("ANSWER_CHANGE"));
        db.update("UPDATE ai_disclosures SET reason='제출 이후 강제 변경' WHERE participant_id=?",f.pid());
        when(ai.getModel()).thenReturn("test-gemini");when(ai.recommendLevels(any())).thenAnswer(call->validOutput(call.getArgument(0)));
        worker.processJob(job.id());job=latest(f);assertThat(job.status()).isEqualTo("DONE");assertThat(job.result().total()).isEqualByComparingTo("75.00");
        assertThat(repository.getInput(job.id()).sources()).noneMatch(s->s.text().contains("제출 이후 강제 변경"));
        String path=root(f)+"/"+job.id();
        var review=ok(body(request(put(path+"/review"),teacher),new ReviewRequest(0,List.of(),"CONFIRMED_OK","학생 근거를 확인했습니다.")));
        assertThat(review.path("revision").asInt()).isEqualTo(1);
        mvc.perform(body(request(put(path+"/review"),teacher),new ReviewRequest(0,List.of(),"CONFIRMED_OK","동시 수정"))).andExpect(status().isConflict());
        mvc.perform(body(request(post(path+"/confirm"),teacher),new ConfirmRequest(1))).andExpect(status().isOk()).andExpect(jsonPath("confirmed").value(true));
        mvc.perform(request(get(path+"/review/history"),teacher)).andExpect(jsonPath("$.length()").value(2));
        var firstId=job.id();as(teacher);var second=service.queueAnalysis(f.id(),f.pid());worker.processJob(second.id());
        assertThat(service.getRuns(f.id(),f.pid())).hasSize(2);
        mvc.perform(body(request(post(root(f)+"/"+firstId+"/confirm"),teacher),new ConfirmRequest(2))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("PROCESS_RESULT_STALE"));
    }
    @Test void fiveQuestionsAreBatchedAndGlobalTransparencyIsCountedOnce() {
        as(teacher);var e=evaluations.create("다문항 평가",60);
        var questions=new ArrayList<QuestionInput>();for(int i=1;i<=5;i++)questions.add(new QuestionInput(i,"문항"+i,"과제 조건을 설명하세요."));
        var configured=work.configure(e.id(),new Configure(AiPolicy.FULL,questions));
        var weights=configured.questions().stream().map(q->new QuestionWeight(q.id(),new BigDecimal("20"))).toList();
        service.savePolicy(e.id(),new PolicyRequest(0,"학교 과제","생성 허용",NoAiPolicy.HOLD,weights));
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        for(var q:configured.questions())work.save(e.id(),p.participantToken(),q.id(),new SaveAnswer("학생이 과제 조건과 근거를 설명한 최종 답안입니다.",0,AnswerSource.DIRECT,null));
        var observed=work.reserve(e.id(),p.participantToken(),configured.questions().get(0).id(),new Ask("batch-observed","개념 설명해줘"),"stub","test");work.finish(observed,"개념 설명",null);
        var preview=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),preview.reviewToken());
        var job=service.getRuns(e.id(),p.participantId()).get(0);
        when(ai.recommendLevels(any())).thenAnswer(call->validOutput(call.getArgument(0)));worker.processJob(job.id());
        var result=service.getRuns(e.id(),p.participantId()).get(0).result();
        assertThat(result.cells()).hasSize(21);assertThat(result.total()).isEqualByComparingTo("75.00");
        assertThat(result.cells().stream().filter(c->c.criterion()==Criterion.TRANSPARENCY).count()).isEqualTo(1);
        verify(ai,times(2)).recommendLevels(any());
    }
    @Test void missingPolicyIsHeldAndProviderFailureNeverBecomesZero() {
        var f=fixture(false,true);var job=latest(f);worker.processJob(job.id());
        var held=latest(f);assertThat(held.result().total()).isNull();assertThat(held.status()).isEqualTo("HOLD");verify(ai,never()).recommendLevels(any());
        var configured=fixture(true,true);var pending=latest(configured);
        when(ai.recommendLevels(any())).thenThrow(new ProcessGradingAiClient.GradingFailure("GRADING_AI_TIMEOUT"));worker.processJob(pending.id());
        assertThat(latest(configured).status()).isEqualTo("FAILED");assertThat(latest(configured).result()).isNull();
    }
    @Test void policyMustCoverEveryQuestionBeforeStartAndRemainVersionChecked() throws Exception {
        as(teacher);var e=evaluations.create("정책 검증",60);var q=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","문제")))).questions().get(0).id();
        String path="/api/teacher/evaluations/"+e.id()+"/process-grading/policy";
        mvc.perform(body(request(put(path),teacher),new PolicyRequest(0,"목표","규칙",NoAiPolicy.HOLD,List.of(new QuestionWeight(q,new BigDecimal("50")))))).andExpect(status().isBadRequest());
        var valid=new PolicyRequest(0,"목표","규칙",NoAiPolicy.NOT_APPLICABLE,List.of(new QuestionWeight(q,new BigDecimal("100"))));
        mvc.perform(body(request(put(path),teacher),valid)).andExpect(status().isOk());mvc.perform(body(request(put(path),teacher),valid)).andExpect(status().isConflict());
        as(teacher);var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        mvc.perform(body(request(put(path),teacher),new PolicyRequest(1,"목표","규칙",NoAiPolicy.HOLD,valid.questionWeights()))).andExpect(status().isConflict());
        var preview=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),preview.reviewToken());
        var f=new Fixture(e.id(),p.participantId(),p.participantToken(),q);var job=latest(f);worker.processJob(job.id());assertThat(latest(f).status()).isEqualTo("NOT_APPLICABLE");
    }
    @Test void foreignTeacherCannotReadEvidenceAndPendingRunCannotDuplicate() throws Exception {
        var f=fixture(true,true);var job=latest(f);var outsider=newTeacher();
        mvc.perform(request(get(root(f)+"/"+job.id()+"/evidence"),outsider)).andExpect(status().isNotFound());
        mvc.perform(get(root(f))).andExpect(status().isUnauthorized());
        mvc.perform(request(post(root(f)),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("PROCESS_ANALYSIS_PENDING"));
        assertThat(repository.getJobs(f.id(),f.pid())).hasSize(1);
    }
    @Test void fabricatedEvidenceIsHeldAndCannotBeConfirmedWithoutTeacherCompletion() throws Exception {
        var f=fixture(true,true);var job=latest(f);var output=validOutput(repository.getInput(job.id()));var records=new ArrayList<>(output.recommendations());var r=records.get(0);
        records.set(0,new Recommendation(r.questionId(),r.criterion(),r.level(),r.reason(),r.unmetNextLevel(),List.of(new Condition("PURPOSE_L4",List.of(new Citation("foreign-student","위조 인용")))),List.of(),List.of()));
        when(ai.recommendLevels(any())).thenReturn(new AiOutput(output.compliance(),records));worker.processJob(job.id());assertThat(latest(f).result().total()).isNull();
        String path=root(f)+"/"+job.id();
        mvc.perform(body(request(put(path+"/review"),teacher),new ReviewRequest(0,List.of(),"CONFIRMED_OK","검토"))).andExpect(status().isOk());
        mvc.perform(body(request(post(path+"/confirm"),teacher),new ConfirmRequest(1))).andExpect(status().isConflict());
        var override=new LevelOverride(f.question(),Criterion.PURPOSE,4,"교사가 실제 근거를 확인했습니다.");
        mvc.perform(body(request(put(path+"/review"),teacher),new ReviewRequest(1,List.of(override),"CONFIRMED_OK","검토 완료"))).andExpect(status().isOk());
        mvc.perform(body(request(post(path+"/confirm"),teacher),new ConfirmRequest(2))).andExpect(status().isOk());
    }
    @Test void analysisViewSeparatesSimilarityFromContributionAndUsesTeacherReview() throws Exception {
        var f=fixture(true,true);var job=latest(f);
        when(ai.recommendLevels(any())).thenAnswer(call->validOutput(call.getArgument(0)));
        String path="/api/teacher/evaluations/"+f.id()+"/process-grading/participants/"+f.pid()+"/analysis";
        mvc.perform(request(get(path),teacher)).andExpect(jsonPath("status").value("PENDING")).andExpect(jsonPath("total").isEmpty());
        worker.processJob(job.id());
        mvc.perform(request(get(path),teacher)).andExpect(status().isOk())
            .andExpect(jsonPath("rubricVersion").value(RUBRIC_VERSION)).andExpect(jsonPath("total").value(75))
            .andExpect(jsonPath("criteria.length()").value(5)).andExpect(jsonPath("studentContribution.level").value(4))
            .andExpect(jsonPath("studentContribution.score").value(18.75)).andExpect(jsonPath("studentContribution.label").value("잘함"))
            .andExpect(jsonPath("textSimilarity.basis").value("TEXT_SIMILARITY_ESTIMATE"));
        as(teacher);service.saveReview(f.id(),f.pid(),job.id(),new ReviewRequest(0,List.of(new LevelOverride(f.question(),Criterion.CONTRIBUTION,5,"학생의 핵심 판단 확인")),"CONFIRMED_OK","확인"));
        mvc.perform(request(get(path),teacher)).andExpect(jsonPath("studentContribution.level").value(5)).andExpect(jsonPath("total").value(81.25)).andExpect(jsonPath("confirmed").value(false));
        mvc.perform(request(get(path),newTeacher())).andExpect(status().isNotFound());
    }
}
