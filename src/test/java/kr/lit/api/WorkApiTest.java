package kr.lit.api;

import org.junit.jupiter.api.Test;
import kr.lit.auth.AuthModels.Tokens;
import java.util.*;
import java.util.concurrent.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkApiTest extends AuthTestSupport {
    record Fixture(String id,String pin,String participantId,String q1,String q2) {}
    Fixture fixture(AiPolicy policy,boolean start) {
        as(teacher);var e=evaluations.create("문항 평가",60);
        var config=work.configure(e.id(),new Configure(policy,List.of(new QuestionInput(1,"문항1","2+2는?"),new QuestionInput(2,"문항2","풀이를 쓰세요"))));
        as(student);var joined=evaluations.join(e.pin(),"학생",student.user().id());
        if(start){as(teacher);evaluations.start(e.id());}
        as(student);return new Fixture(e.id(),e.pin(),joined.participantId(),config.questions().get(0).id(),config.questions().get(1).id());
    }
    String base(Fixture f) {return "/api/evaluations/"+f.id();}
    String answer(Fixture f) {return base(f)+"/questions/"+f.q1()+"/answer";}
    String ai(Fixture f) {return base(f)+"/questions/"+f.q1()+"/ai/questions";}
    SaveAnswer direct(String s,int version) {return new SaveAnswer(s,version,AnswerSource.DIRECT,null);}
    @Test void completeFlowWithImmutableSubmission() throws Exception {
        var f=fixture(AiPolicy.HINT_ONLY,true);
        ok(request(body(put(answer(f)),direct("첫 답",0)),student));
        ok(request(body(put(base(f)+"/questions/"+f.q2()+"/answer"),direct("두 번째 답",0)),student));
        mvc.perform(request(get(base(f)+"/questions/"+f.q1()),student)).andExpect(jsonPath("answer.content").value("첫 답"));
        var ask=new Ask("ask-1","힌트 주세요");var first=ok(request(body(post(ai(f)),ask),student));
        assertThat(first.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(first.get("answerText").asText()).contains("테스트 응답");
        assertThat(ok(request(body(post(ai(f)),ask),student)).get("id")).isEqualTo(first.get("id"));
        ok(request(body(put(answer(f)),new SaveAnswer("AI 참고 답",1,AnswerSource.AI_REFERENCED,"ask-1")),student));
        var preview=ok(request(get(base(f)+"/submission-preview"),student));
        ok(request(body(put(answer(f)),direct("최종 답",2)),student));
        mvc.perform(request(body(post(base(f)+"/submission"),new Confirm(preview.get("reviewToken").asText())),student)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("REVIEW_CHANGED"));
        preview=ok(request(get(base(f)+"/submission-preview"),student));var confirm=new Confirm(preview.get("reviewToken").asText());
        var submission=ok(request(body(post(base(f)+"/submission"),confirm),student));
        assertThat(ok(request(body(post(base(f)+"/submission"),confirm),student))).isEqualTo(submission);
        assertThat(submission.at("/snapshot/answers/0/answer/content").asText()).isEqualTo("최종 답");
        mvc.perform(request(body(put(answer(f)),direct("불가",3)),student)).andExpect(status().isConflict());
        mvc.perform(request(body(post(ai(f)),new Ask("after-submit","질문")),student)).andExpect(status().isConflict());
        mvc.perform(request(get(base(f)+"/state"),student)).andExpect(jsonPath("screen").value("SUBMITTED"));
        mvc.perform(request(get(base(f)+"/questions"),student)).andExpect(jsonPath("readOnly").value(true));
        assertThat(ok(request(get(base(f)+"/learning-events"),student)).findValuesAsText("type")).contains("ANSWER_SAVED","AI_REQUESTED","AI_COMPLETED","SUBMITTED");
    }
    @Test void accountIsolationAndTeacherOwnership() throws Exception {
        var f=fixture(AiPolicy.FULL,false);
        as(otherStudent);evaluations.join(f.pin(),"다른 학생",otherStudent.user().id());as(teacher);evaluations.start(f.id());
        ok(request(body(put(answer(f)),direct("비공개 답",0)),student));
        ok(request(body(post(ai(f)),new Ask("private","비공개 질문")),student));
        assertThat(ok(request(get(base(f)+"/questions"),otherStudent)).toString()).doesNotContain("비공개 답");
        assertThat(ok(request(get(base(f)+"/conversations"),otherStudent)).size()).isZero();
        mvc.perform(request(body(put(answer(f)),new SaveAnswer("도용",0,AnswerSource.AI_USED,"private")),otherStudent)).andExpect(status().isBadRequest());
        var stranger=newTeacher();
        for(String suffix:List.of("","/questions","/participants","/qr"))mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+suffix),stranger)).andExpect(status().isNotFound());
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/participants/"+f.participantId()+"/learning-events"),stranger)).andExpect(status().isNotFound());
        mvc.perform(request(post("/api/teacher/evaluations/"+f.id()+"/end"),stranger)).andExpect(status().isNotFound());
    }
    @Test void policyWaitingAndValidation() throws Exception {
        var f=fixture(AiPolicy.DISABLED,false);
        mvc.perform(request(get(base(f)+"/questions"),student)).andExpect(status().isConflict());
        as(teacher);evaluations.start(f.id());
        mvc.perform(request(body(post(ai(f)),new Ask("disabled","질문")),student)).andExpect(status().isForbidden());
        mvc.perform(request(body(put(answer(f)),direct("가".repeat(10001),0)),student)).andExpect(status().isBadRequest());
        mvc.perform(request(get(base(f)+"/conversations").param("limit","201"),student)).andExpect(status().isBadRequest());
        mvc.perform(request(get(base(f)+"/questions/unknown"),student)).andExpect(status().isNotFound());
        var configuration=new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","본문")));
        mvc.perform(request(body(put("/api/teacher/evaluations/"+f.id()+"/questions"),configuration),teacher)).andExpect(status().isConflict());
    }
    @Test void pendingRecoveryBlocksSubmissionAndNeverOverwritesLateResponse() throws Exception {
        var f=fixture(AiPolicy.FULL,true);var ask=new Ask("pending","질문");
        var reservation=work.reserve(f.id(),student.user().id(),f.q1(),ask,"stub","local-stub");
        mvc.perform(request(body(post(ai(f)),ask),student)).andExpect(status().isAccepted());
        as(student);var preview=work.review(f.id(),student.user().id());assertThat(preview.canConfirm()).isFalse();
        mvc.perform(request(body(post(base(f)+"/submission"),new Confirm(preview.reviewToken())),student)).andExpect(status().isConflict());
        db.update("UPDATE ai_turns SET created_at=DATEADD('SECOND',-150,CURRENT_TIMESTAMP) WHERE participant_id=?",f.participantId());
        work.expirePending();var result=work.finish(reservation,"늦은 응답",null);
        assertThat(result.errorCode()).isEqualTo("AI_REQUEST_ABANDONED");assertThat(result.answerText()).isNull();
    }
    @Test void optimisticConcurrencyAndEventDeduplication() throws Exception {
        var f=fixture(AiPolicy.DISABLED,true);var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        Callable<Boolean> save=()->{as(student);gate.await();try{work.save(f.id(),student.user().id(),f.q1(),direct("동시 수정",0));return true;}catch(ApiError.Failure e){return false;}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}};
        try{var a=executor.submit(save);var b=executor.submit(save);gate.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);}finally{executor.shutdownNow();}
        as(student);assertThat(work.learningEvents(f.id(),student.user().id(),0,100)).hasSize(1);
    }
    @Test void blankAnswerConfirmationAndEndedReadOnly() throws Exception {
        var f=fixture(AiPolicy.DISABLED,true);var preview=work.review(f.id(),student.user().id());assertThat(preview.blankQuestionIds()).hasSize(2);
        var submitted=work.submit(f.id(),student.user().id(),preview.reviewToken());as(teacher);evaluations.end(f.id());as(student);
        assertThat(work.submission(f.id(),student.user().id())).isEqualTo(submitted);
        mvc.perform(request(body(put(answer(f)),direct("늦은 답",0)),student)).andExpect(status().isConflict());
    }
    @Test void cursorAndAiRequestIdRules() throws Exception {
        var f=fixture(AiPolicy.FULL,true);
        var a=ok(request(body(post(ai(f)),new Ask("first","첫 질문")),student));
        var b=ok(request(body(post(ai(f)),new Ask("second","두 번째 질문")),student));
        var page=ok(request(get(base(f)+"/conversations").param("afterId",a.get("id").asText()).param("limit","1"),student));
        assertThat(page.get(0).get("id")).isEqualTo(b.get("id"));
        mvc.perform(request(body(post(ai(f)),new Ask("first","바뀐 질문")),student)).andExpect(status().isConflict());
    }
    @Test void expiryAndUnjoinedAccount() throws Exception {
        var f=fixture(AiPolicy.FULL,true);
        mvc.perform(request(get(base(f)+"/questions"),otherStudent)).andExpect(status().isUnauthorized());
        db.update("UPDATE evaluations SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",f.id());
        mvc.perform(request(body(put(answer(f)),direct("답",0)),student)).andExpect(status().isGone());
        mvc.perform(request(get(base(f)+"/questions"),student)).andExpect(jsonPath("status").value("EXPIRED"));
    }
    @Test void duplicatePositionsRejectedBeforeStart() throws Exception {
        var f=fixture(AiPolicy.DISABLED,false);
        var input=new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"하나","내용"),new QuestionInput(1,"둘","내용")));
        mvc.perform(request(body(put("/api/teacher/evaluations/"+f.id()+"/questions"),input),teacher)).andExpect(status().isBadRequest());
    }
}
