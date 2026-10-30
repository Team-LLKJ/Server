package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static kr.lit.api.WorkModels.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class TimeLimitApiTest extends AuthTestSupport {
    @Autowired EvaluationExpiryJob expiry;
    record Fixture(String id,String q,String first,String firstToken,String second,String secondToken) {}
    Fixture fixture(boolean start) {
        as(teacher);var e=evaluations.create("시간 제한",60,"안내",10);
        var config=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","설명"))));
        var p=evaluations.join(e.pin(),"첫 학생",null);var p2=evaluations.join(e.pin(),"둘째 학생",null);
        if(start)evaluations.start(e.id());
        return new Fixture(e.id(),config.questions().get(0).id(),p.participantId(),p.participantToken(),p2.participantId(),p2.participantToken());
    }
    void expire(String id) {db.update("UPDATE evaluations SET ends_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP),expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",id);}
    @Test void countdownStartsWhenTeacherStartsAndReturnsAuthoritativeClock() throws Exception {
        var f=fixture(false);
        mvc.perform(get("/api/student/evaluations/"+f.id()+"/state").header("X-Participant-Token",f.firstToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("state").value("WAITING")).andExpect(jsonPath("endsAt").isEmpty()).andExpect(jsonPath("serverNow").isNotEmpty());
        db.update("UPDATE evaluations SET expires_at=DATEADD('SECOND',5,CURRENT_TIMESTAMP) WHERE id=?",f.id());
        Instant before=Instant.now();var started=evaluations.start(f.id());
        assertThat(started.endsAt()).isAfter(before.plusSeconds(599)).isBefore(Instant.now().plusSeconds(601));
        var startedAt=db.queryForObject("SELECT started_at FROM evaluations WHERE id=?",OffsetDateTime.class,f.id()).toInstant();
        assertThat(started.endsAt()).isEqualTo(startedAt.plusSeconds(600));
        var state=ok(get("/api/student/evaluations/"+f.id()+"/state").header("X-Participant-Token",f.firstToken()));
        assertThat(Instant.parse(state.path("endsAt").asText())).isEqualTo(started.endsAt());
        assertThat(Instant.parse(state.path("serverNow").asText())).isBetween(before,Instant.now());
        mvc.perform(get("/api/evaluations/"+f.id()+"/state").header("X-Participant-Token",f.firstToken())).andExpect(status().isOk()).andExpect(jsonPath("endsAt").value(state.path("endsAt").asText()));
    }
    @Test void stateRequiresCorrectParticipantAndDurationIsConfigurable() throws Exception {
        var f=fixture(false);var other=fixture(false);
        mvc.perform(get("/api/student/evaluations/"+f.id()+"/state")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/evaluations/"+f.id()+"/state").header("X-Participant-Token",other.firstToken())).andExpect(status().isUnauthorized());
        mvc.perform(body(request(put("/api/teacher/evaluations/"+f.id()+"/configuration"),teacher),Map.of("aiPolicy","FULL","durationMinutes",7,"questions",List.of(Map.of("position",1,"title","문항","content","문제")))))
                .andExpect(status().isOk()).andExpect(jsonPath("durationMinutes").value(7));
        as(teacher);var started=evaluations.start(f.id());
        assertThat(started.endsAt()).isAfter(Instant.now().plusSeconds(419));
        mvc.perform(body(request(post("/api/teacher/evaluations"),teacher),Map.of("title","잘못된 시간","expiresInMinutes",60,"durationMinutes",0))).andExpect(status().isBadRequest());
    }
    @Test void jobSubmitsSavedAnswersForDisconnectedAndLeftStudents() {
        var f=fixture(true);work.save(f.id(),f.firstToken(),f.q(),new SaveAnswer("최종 저장 답안",0,AnswerSource.DIRECT,null));
        work.save(f.id(),f.secondToken(),f.q(),new SaveAnswer("나간 학생 답안",0,AnswerSource.DIRECT,null));
        evaluations.participant(f.id(),f.secondToken(),true);
        expire(f.id());SecurityContextHolder.clearContext();expiry.closeExpired();
        assertThat(db.queryForObject("SELECT state FROM evaluations WHERE id=?",String.class,f.id())).isEqualTo("ENDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE evaluation_id=?",Integer.class,f.id())).isEqualTo(2);
        var submitted=work.submission(f.id(),f.firstToken());assertThat(submitted.submissionMode()).isEqualTo("AUTO_DEADLINE");
        assertThat(submitted.snapshot().at("/answers/0/answer/content").asText()).isEqualTo("최종 저장 답안");
        assertThat(db.queryForObject("SELECT snapshot FROM submissions WHERE participant_id=?",String.class,f.second())).contains("나간 학생 답안");
        expiry.closeExpired();assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_events WHERE evaluation_id=? AND event_type='SUBMITTED'",Integer.class,f.id())).isEqualTo(2);
    }
    @Test void statePollingClosesDeadlineEvenBeforeNextJobAndLateSaveIsRejected() throws Exception {
        var f=fixture(true);expire(f.id());
        mvc.perform(body(put("/api/student/evaluations/"+f.id()+"/questions/"+f.q()+"/answer").header("X-Participant-Token",f.firstToken()),Map.of("content","늦은 입력","expectedVersion",0,"source","DIRECT")))
                .andExpect(status().isGone()).andExpect(jsonPath("code").value("PIN_EXPIRED"));
        mvc.perform(get("/api/student/evaluations/"+f.id()+"/state").header("X-Participant-Token",f.firstToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("state").value("ENDED")).andExpect(jsonPath("screen").value("SUBMITTED"));
        assertThat(work.submission(f.id(),f.firstToken()).submissionMode()).isEqualTo("AUTO_DEADLINE");
    }
    @Test void manualEndKeepsManualSubmissionAndClosesPendingAiBeforeSnapshot() {
        var f=fixture(true);work.save(f.id(),f.firstToken(),f.q(),new SaveAnswer("직접 제출",0,AnswerSource.DIRECT,null));
        var review=work.review(f.id(),f.firstToken());var original=work.submit(f.id(),f.firstToken(),review.reviewToken());
        var pending=work.reserve(f.id(),f.secondToken(),f.q(),new Ask("pending-auto","힌트"),"stub","local-stub");
        var ended=evaluations.end(f.id());assertThat(ended.state()).isEqualTo("ENDED");
        var first=work.submission(f.id(),f.firstToken());assertThat(first.submissionMode()).isEqualTo("MANUAL");assertThat(first.submittedAt()).isEqualTo(original.submittedAt());
        var second=work.submission(f.id(),f.secondToken());assertThat(second.submissionMode()).isEqualTo("AUTO_TEACHER_END");
        assertThat(second.snapshot().at("/conversations/0/state").asText()).isEqualTo("FAILED");
        assertThat(work.finish(pending,"늦은 성공",null).state()).isEqualTo("FAILED");
        evaluations.end(f.id());assertThat(work.submission(f.id(),f.secondToken()).submittedAt()).isEqualTo(second.submittedAt());
    }
    @Test void waitingEvaluationDoesNotGetSubmittedAndEmptyStartedEvaluationDoes() {
        var waiting=fixture(false);evaluations.end(waiting.id());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE evaluation_id=?",Integer.class,waiting.id())).isZero();
        var e=evaluations.create("문항 없는 평가",60);var p=evaluations.join(e.pin(),"빈 답안 학생",null);evaluations.start(e.id());evaluations.end(e.id());
        assertThat(work.submission(e.id(),p.participantToken()).snapshot().path("answers")).isEmpty();
    }
    @Test void concurrentSaveAndEndIncludeAcceptedSaveOrRejectItWithoutChangingSnapshot() throws Exception {
        var f=fixture(true);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var save=pool.submit(()->{as(teacher);gate.await();try {work.save(f.id(),f.firstToken(),f.q(),new SaveAnswer("마지막 저장",0,AnswerSource.DIRECT,null));return true;}
                catch(ApiError.Failure e){assertThat(e.code).isEqualTo("ALREADY_SUBMITTED");return false;}finally{SecurityContextHolder.clearContext();}});
            var end=pool.submit(()->{as(teacher);gate.await();try {evaluations.end(f.id());return true;}finally{SecurityContextHolder.clearContext();}});
            gate.countDown();boolean saved=save.get(10,TimeUnit.SECONDS);end.get(10,TimeUnit.SECONDS);
            String content=work.submission(f.id(),f.firstToken()).snapshot().at("/answers/0/answer/content").asText();
            assertThat(content).isEqualTo(saved?"마지막 저장":"");
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentManualSubmissionAndEndHaveExactlyOneImmutableSubmission() throws Exception {
        var f=fixture(true);var preview=work.review(f.id(),f.firstToken());
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var manual=pool.submit(()->{as(teacher);gate.await();try {work.submit(f.id(),f.firstToken(),preview.reviewToken());}catch(ApiError.Failure e){assertThat(e.code).isEqualTo("ALREADY_SUBMITTED");}finally{SecurityContextHolder.clearContext();}return true;});
            var end=pool.submit(()->{as(teacher);gate.await();try {evaluations.end(f.id());}finally{SecurityContextHolder.clearContext();}return true;});
            gate.countDown();manual.get(10,TimeUnit.SECONDS);end.get(10,TimeUnit.SECONDS);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,f.first())).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_events WHERE participant_id=? AND event_type='SUBMITTED'",Integer.class,f.first())).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
}
