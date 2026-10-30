package kr.lit.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static kr.lit.api.WorkModels.*;

class WorkApiTest extends AuthTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EvaluationService evaluations;
    @Autowired WorkService work;
    @Autowired JdbcTemplate db;
    record Fixture(String id,String pin,String token,String participantId,String q1,String q2) {}
    private String root(Fixture f) { return "/api/evaluations/"+f.id(); }
    private String answerPath(Fixture f) { return root(f)+"/questions/"+f.q1()+"/answer"; }
    private String aiPath(Fixture f) { return root(f)+"/questions/"+f.q1()+"/ai/questions"; }
    private String body(Object value) throws Exception { return json.writeValueAsString(value); }
    JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
    private MockHttpServletRequestBuilder student(MockHttpServletRequestBuilder request,Fixture f) { return request.header("X-Participant-Token",f.token()); }
    private MockHttpServletRequestBuilder payload(MockHttpServletRequestBuilder request,Object body) throws Exception { return request.contentType("application/json").content(body(body)); }
    private SaveAnswer direct(String text,int version) { return new SaveAnswer(text,version,AnswerSource.DIRECT,null); }
    private Fixture fixture(AiPolicy policy,boolean start) {
        as(teacher);var e=evaluations.create("추가 기능 평가",60);
        var c=work.configure(e.id(),new Configure(policy,List.of(new QuestionInput(1,"문항 1","2+2는?"),new QuestionInput(2,"문항 2","생각을 설명하세요."))));
        var p=evaluations.join(e.pin(),"학생",null);
        if(start) evaluations.start(e.id());
        return new Fixture(e.id(),e.pin(),p.participantToken(),p.participantId(),c.questions().get(0).id(),c.questions().get(1).id());
    }
    @Test void fullAnswerConversationAndFinalSubmissionFlow() throws Exception {
        var f=fixture(AiPolicy.HINT_ONLY,true);
        ok(student(get(root(f)+"/questions"),f));
        var saved=ok(student(payload(put(answerPath(f)),direct("처음 답안",0)),f));
        assertThat(saved.get("version").asInt()).isEqualTo(1);
        ok(student(payload(put(root(f)+"/questions/"+f.q2()+"/answer"),direct("두 번째 답안",0)),f));
        var selected=ok(student(get(root(f)+"/questions/"+f.q1()),f));
        assertThat(selected.at("/answer/content").asText()).isEqualTo("처음 답안");
        var ask=new Ask("question-1","개념 힌트를 주세요");
        var turn=ok(student(payload(post(aiPath(f)),ask),f));
        assertThat(turn.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(turn.get("answerText").asText()).contains("테스트 응답");
        assertThat(turn.get("aiPolicy").asText()).isEqualTo("HINT_ONLY");
        var retry=ok(student(payload(post(aiPath(f)),ask),f));
        assertThat(retry.get("id")).isEqualTo(turn.get("id"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_turns WHERE participant_id=?",Integer.class,f.participantId())).isEqualTo(1);
        mvc.perform(student(payload(post(aiPath(f)),new Ask("question-1","다른 질문")),f)).andExpect(status().isConflict());
        ok(student(payload(put(answerPath(f)),new SaveAnswer("AI를 참고해 고침",1,AnswerSource.AI_REFERENCED,"question-1")),f));
        var preview=ok(student(get(root(f)+"/submission-preview"),f));
        assertThat(preview.get("canConfirm").asBoolean()).isTrue();
        ok(student(payload(put(answerPath(f)),direct("최종 답안",2)),f));
        mvc.perform(student(payload(post(root(f)+"/submission"),new Confirm(preview.get("reviewToken").asText())),f)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("REVIEW_CHANGED"));
        preview=ok(student(get(root(f)+"/submission-preview"),f));
        var confirm=new Confirm(preview.get("reviewToken").asText());
        var result=ok(student(payload(post(root(f)+"/submission"),confirm),f));
        var repeated=ok(student(payload(post(root(f)+"/submission"),confirm),f));
        assertThat(repeated).isEqualTo(result);
        assertThat(result.at("/snapshot/answers/0/answer/content").asText()).isEqualTo("최종 답안");
        mvc.perform(student(payload(put(answerPath(f)),direct("수정 불가",3)),f)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("ALREADY_SUBMITTED"));
        mvc.perform(student(payload(post(aiPath(f)),new Ask("after-submit","질문")),f)).andExpect(status().isConflict());
        mvc.perform(student(get(root(f)+"/questions"),f)).andExpect(jsonPath("readOnly").value(true)).andExpect(jsonPath("status").value("SUBMITTED"));
        mvc.perform(student(get(root(f)+"/state"),f)).andExpect(jsonPath("screen").value("SUBMITTED"));
        mvc.perform(student(get(root(f)+"/conversations"),f)).andExpect(jsonPath("$[0].questionText").value(ask.text()));
        var events=ok(student(get(root(f)+"/learning-events"),f));
        assertThat(events.findValuesAsText("type")).contains("ANSWER_SAVED","AI_REQUESTED","AI_COMPLETED","SUBMITTED");
        assertThat(events.toString()).contains("AI_REFERENCED","처음 답안","최종 답안");
        evaluations.end(f.id());
        assertThat(ok(student(get(root(f)+"/submission"),f))).isEqualTo(result);
    }
    @Test void blankAnswersAreFlaggedButConfirmationCanSubmitThem() throws Exception {
        var f=fixture(AiPolicy.DISABLED,true);
        var preview=ok(student(get(root(f)+"/submission-preview"),f));
        assertThat(preview.get("blankQuestionIds").size()).isEqualTo(2);
        ok(student(payload(post(root(f)+"/submission"),new Confirm(preview.get("reviewToken").asText())),f));
        mvc.perform(student(payload(post(root(f)+"/submission"),new Confirm("a".repeat(64))),f)).andExpect(status().isConflict());
    }
    @Test void waitingAndPolicyAndConfigurationAreEnforced() throws Exception {
        var f=fixture(AiPolicy.DISABLED,false);
        mvc.perform(student(get(root(f)+"/questions"),f)).andExpect(status().isConflict());
        mvc.perform(student(payload(put(answerPath(f)),direct("답",0)),f)).andExpect(status().isConflict());
        evaluations.start(f.id());
        mvc.perform(student(payload(post(aiPath(f)),new Ask("disabled","질문")),f)).andExpect(status().isForbidden());
        var config=new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","내용")));
        mvc.perform(payload(put("/api/teacher/evaluations/"+f.id()+"/questions"),config).header("Authorization","Bearer "+teacher.accessToken())).andExpect(status().isConflict());
        mvc.perform(get("/api/teacher/evaluations/"+f.id()+"/questions")).andExpect(status().isUnauthorized());
    }
    @Test void ownershipAndValidationAreEnforced() throws Exception {
        var f=fixture(AiPolicy.FULL,true);
        var other=fixture(AiPolicy.FULL,true);
        mvc.perform(get(root(f)+"/questions").header("X-Participant-Token",other.token())).andExpect(status().isUnauthorized());
        mvc.perform(student(get(root(f)+"/questions/"+other.q1()),f)).andExpect(status().isNotFound());
        mvc.perform(student(payload(put(answerPath(f)),new SaveAnswer("참고",0,AnswerSource.AI_USED,"foreign")),f)).andExpect(status().isBadRequest());
        mvc.perform(student(payload(post(aiPath(f)),new Ask("empty","   ")),f)).andExpect(status().isBadRequest());
        mvc.perform(student(payload(put(answerPath(f)),direct("가".repeat(349526),0)),f)).andExpect(status().isPayloadTooLarge());
        mvc.perform(student(get(root(f)+"/conversations").param("limit","201"),f)).andExpect(status().isBadRequest());
        mvc.perform(student(get(root(f)+"/learning-events").param("afterId","-1"),f)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/teacher/evaluations/"+f.id()+"/participants/"+other.participantId()+"/learning-events").header("Authorization","Bearer "+teacher.accessToken())).andExpect(status().isNotFound());
        var pending=fixture(AiPolicy.DISABLED,false);
        var duplicate=new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"하나","내용"),new QuestionInput(1,"둘","내용")));
        mvc.perform(payload(put("/api/teacher/evaluations/"+pending.id()+"/questions"),duplicate).header("Authorization","Bearer "+teacher.accessToken())).andExpect(status().isBadRequest());
    }
    @Test void anotherStudentCannotSeeAnswersOrChat() throws Exception {
        var f=fixture(AiPolicy.FULL,false);
        var other=evaluations.join(f.pin(),"다른 학생",null);
        evaluations.start(f.id());
        work.save(f.id(),f.token(),f.q1(),direct("비공개 답안",0));
        ok(student(payload(post(aiPath(f)),new Ask("private","비공개 질문")),f));
        var theirQuestions=ok(get(root(f)+"/questions").header("X-Participant-Token",other.participantToken()));
        assertThat(theirQuestions.toString()).doesNotContain("비공개 답안");
        var theirChat=ok(get(root(f)+"/conversations").header("X-Participant-Token",other.participantToken()));
        assertThat(theirChat.size()).isZero();
        mvc.perform(payload(put(answerPath(f)),new SaveAnswer("참고",0,AnswerSource.AI_USED,"private")).header("X-Participant-Token",other.participantToken())).andExpect(status().isBadRequest());
    }
    @Test void simultaneousEditsKeepOneVersionAndOneEvent() throws Exception {
        var f=fixture(AiPolicy.DISABLED,true);
        var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        Callable<Boolean> save=()->{gate.await();try{work.save(f.id(),f.token(),f.q1(),direct("동시 수정",0));return true;}catch(ApiError.Failure e){return false;}};
        try {
            var first=executor.submit(save);var second=executor.submit(save);gate.countDown();
            assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(true,false);
        } finally {executor.shutdownNow();}
        assertThat(work.select(f.id(),f.token(),f.q1()).answer().version()).isEqualTo(1);
        assertThat(work.learningEvents(f.id(),f.token(),0,100)).hasSize(1);
    }
    @Test void pendingAiBlocksSubmissionAndRecoversWithoutDuplicatingRequests() throws Exception {
        var f=fixture(AiPolicy.FULL,true);
        var ask=new Ask("pending","질문");
        var reservation=work.reserve(f.id(),f.token(),f.q1(),ask,"stub","local-stub");
        mvc.perform(student(payload(post(aiPath(f)),ask),f)).andExpect(status().isAccepted()).andExpect(jsonPath("state").value("PENDING"));
        mvc.perform(student(payload(post(aiPath(f)),new Ask("second","다른 질문")),f)).andExpect(status().isConflict());
        var review=work.review(f.id(),f.token());assertThat(review.canConfirm()).isFalse();
        mvc.perform(student(payload(post(root(f)+"/submission"),new Confirm(review.reviewToken())),f)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("AI_IN_PROGRESS"));
        db.update("UPDATE ai_turns SET created_at=DATEADD('SECOND',-150,CURRENT_TIMESTAMP) WHERE participant_id=?",f.participantId());
        work.expirePending();
        var abandoned=work.finish(reservation,"늦게 온 응답",null);
        assertThat(abandoned.state()).isEqualTo("FAILED");
        assertThat(abandoned.errorCode()).isEqualTo("AI_REQUEST_ABANDONED");
        assertThat(abandoned.answerText()).isNull();
        review=work.review(f.id(),f.token());assertThat(review.canConfirm()).isTrue();
        work.submit(f.id(),f.token(),review.reviewToken());
    }
    @Test void endedAndExpiredEvaluationsAreReadOnlyAndFailedAiIsLogged() throws Exception {
        var f=fixture(AiPolicy.FULL,true);
        var r=work.reserve(f.id(),f.token(),f.q1(),new Ask("failed","질문"),"openai","test-model");
        var failed=work.finish(r,null,"AI_TIMEOUT");
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(work.learningEvents(f.id(),f.token(),0,100).stream().map(Event::type)).contains("AI_FAILED");
        evaluations.end(f.id());
        mvc.perform(student(payload(put(answerPath(f)),direct("답안",0)),f)).andExpect(status().isConflict());
        mvc.perform(student(get(root(f)+"/questions"),f)).andExpect(status().isOk()).andExpect(jsonPath("readOnly").value(true));
        var expired=fixture(AiPolicy.FULL,true);
        db.update("UPDATE evaluations SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",expired.id());
        mvc.perform(student(payload(put(answerPath(expired)),direct("답안",0)),expired)).andExpect(status().isGone());
        mvc.perform(student(get(root(expired)+"/questions"),expired)).andExpect(jsonPath("status").value("EXPIRED"));
    }
    @Test void conversationsUseStableIdCursor() throws Exception {
        var f=fixture(AiPolicy.FULL,true);
        var a=ok(student(payload(post(aiPath(f)),new Ask("first","첫 질문")),f));
        var b=ok(student(payload(post(aiPath(f)),new Ask("next","두 번째 질문")),f));
        var page=ok(student(get(root(f)+"/conversations").param("afterId",a.get("id").asText()).param("limit","1"),f));
        assertThat(page.size()).isEqualTo(1);assertThat(page.get(0).get("id")).isEqualTo(b.get("id"));
    }
}
