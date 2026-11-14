package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ParticipantExpulsionApiTest extends AuthTestSupport {
    @Autowired ClassEventRepository liveEvents;
    @Autowired ReportService reports;
    record Fixture(String id,String pid,String token,String qid,String pin){}
    Fixture createFixture(boolean start){as(teacher);var e=evaluations.create("참여자 추방",60);String q=work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"문항","근거를 설명하세요.")))).questions().get(0).id();var p=evaluations.join(e.pin(),"학생",null);if(start)evaluations.start(e.id());return new Fixture(e.id(),p.participantId(),p.participantToken(),q,e.pin());}
    String path(Fixture f){return "/api/teacher/evaluations/"+f.id()+"/participants/"+f.pid();}
    void expel(Fixture f)throws Exception{mvc.perform(request(delete(path(f)),teacher)).andExpect(status().isNoContent());}
    @Test void teacherExpulsionIsIdempotentAndBlocksEveryReconnectPath()throws Exception {
        var f=createFixture(false);expel(f);var at=db.queryForObject("SELECT expelled_at FROM participants WHERE id=?",java.time.OffsetDateTime.class,f.pid());expel(f);
        assertThat(db.queryForObject("SELECT active FROM participants WHERE id=?",Boolean.class,f.pid())).isFalse();assertThat(db.queryForObject("SELECT expelled_at FROM participants WHERE id=?",java.time.OffsetDateTime.class,f.pid())).isEqualTo(at);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_events WHERE participant_id=? AND event_type='PARTICIPANT_EXPELLED'",Integer.class,f.pid())).isEqualTo(1);
        for(String route:List.of("/api/evaluations/"+f.id()+"/state","/api/evaluations/"+f.id()+"/me","/api/student/evaluations/"+f.id()+"/questions"))mvc.perform(get(route).header("X-Participant-Token",f.token())).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("PARTICIPANT_EXPELLED"));
        mvc.perform(post("/api/evaluations/"+f.id()+"/heartbeat").header("X-Participant-Token",f.token())).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/participation/reconnect"),Map.of("evaluationId",f.id(),"participantToken",f.token()))).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("PARTICIPANT_EXPELLED"));
        mvc.perform(body(post("/api/participation/join"),Map.of("pin",f.pin(),"nickname","학생","participantToken",f.token()))).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/participation/join"),Map.of("pin",f.pin(),"nickname","학생"))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("NICKNAME_TAKEN"));
        as(teacher);assertThat(evaluations.lobby(f.id()).participants()).isEmpty();
        assertThat(liveEvents.getEvents(f.id(),0)).filteredOn(e->e.type().equals("PARTICIPANT_EXPELLED")).hasSize(1);
    }
    @Test void preservesDraftAndHistoryCancelsPendingAiAndRejectsLateSuccess()throws Exception {
        var f=createFixture(true);work.save(f.id(),f.token(),f.qid(),new SaveAnswer("보존할 최신 답안",0,AnswerSource.DIRECT,null));var pending=work.reserve(f.id(),f.token(),f.qid(),new Ask("expel-pending","개념 설명해줘"),"stub","test");expel(f);
        var completed=work.finish(pending,"늦은 응답",null);assertThat(completed.state()).isEqualTo("FAILED");assertThat(completed.errorCode()).isEqualTo("PARTICIPANT_EXPELLED");assertThat(completed.answerText()).isNull();
        assertThat(db.queryForObject("SELECT content FROM answers WHERE participant_id=?",String.class,f.pid())).isEqualTo("보존할 최신 답안");assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,f.pid())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_events WHERE participant_id=? AND event_type IN ('AI_COMPLETED','SUBMITTED')",Integer.class,f.pid())).isZero();
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students"),teacher)).andExpect(jsonPath("items[0].expelled").value(true)).andExpect(jsonPath("items[0].presence").value("LEFT")).andExpect(jsonPath("items[0].warningCount").value(0));
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/submissions"),teacher)).andExpect(jsonPath("items[0].expelled").value(true)).andExpect(jsonPath("items[0].expelledAt").isNotEmpty());
        mvc.perform(body(put("/api/student/evaluations/"+f.id()+"/questions/"+f.qid()+"/answer").header("X-Participant-Token",f.token()),new SaveAnswer("차단돼야 하는 답안",1,AnswerSource.DIRECT,null))).andExpect(status().isForbidden());
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/answers"),teacher)).andExpect(status().isOk());
        as(teacher);evaluations.end(f.id());assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,f.pid())).isEqualTo(1);expel(f);
    }
    @Test void reportAccessIsRevokedButTeacherSubmissionAndReportStayIntact()throws Exception {
        var f=createFixture(true);work.save(f.id(),f.token(),f.qid(),new SaveAnswer("제출 기록 보존",0,AnswerSource.DIRECT,null));var review=work.review(f.id(),f.token());work.submit(f.id(),f.token(),review.reviewToken());as(teacher);var report=reports.teacherGenerate(f.id(),f.pid());String snapshot=db.queryForObject("SELECT snapshot FROM submissions WHERE participant_id=?",String.class,f.pid());expel(f);
        mvc.perform(get("/api/evaluations/"+f.id()+"/report").header("X-Participant-Token",f.token())).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("TEACHER_ONLY"));
        mvc.perform(get("/api/evaluations/"+f.id()+"/state").header("X-Participant-Token",f.token())).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("PARTICIPANT_EXPELLED"));
        assertThat(db.queryForObject("SELECT snapshot FROM submissions WHERE participant_id=?",String.class,f.pid())).isEqualTo(snapshot);as(teacher);assertThat(reports.teacherReport(f.id(),f.pid(),report.version()).id()).isEqualTo(report.id());
    }
    @Test void rejectsAnonymousForeignTeachersWrongEvaluationAndEndedNewExpulsion()throws Exception {
        var f=createFixture(true);mvc.perform(delete(path(f))).andExpect(status().isUnauthorized());mvc.perform(request(delete(path(f)),newTeacher())).andExpect(status().isNotFound());
        var other=createFixture(false);mvc.perform(request(delete("/api/teacher/evaluations/"+f.id()+"/participants/"+other.pid()),teacher)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("PARTICIPANT_NOT_FOUND"));
        assertThat(db.queryForObject("SELECT expelled_at FROM participants WHERE id=?",java.time.OffsetDateTime.class,f.pid())).isNull();as(teacher);evaluations.end(f.id());mvc.perform(request(delete(path(f)),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("EVALUATION_ENDED"));
    }
    @Test void submittedStudentCannotReconnectOrReadReportsAfterLeave()throws Exception {
        // 2026-11-14: 제출한 학생은 다시 들어오기·보고서 모두 막습니다(나가기 호출이 실패해도 같음).
        var f=createFixture(true);var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());as(teacher);reports.teacherGenerate(f.id(),f.pid());
        for(boolean left:List.of(false,true)) {
            if(left)evaluations.participant(f.id(),f.token(),true);
            mvc.perform(get("/api/evaluations/"+f.id()+"/report").header("X-Participant-Token",f.token())).andExpect(status().isForbidden());
            mvc.perform(body(post("/api/participation/reconnect"),Map.of("evaluationId",f.id(),"participantToken",f.token()))).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_PARTICIPANT_TOKEN"));
            mvc.perform(body(post("/api/participation/join"),Map.of("pin",f.pin(),"nickname","학생","participantToken",f.token()))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/student/evaluations/"+f.id()+"/workspace").header("X-Participant-Token",f.token())).andExpect(status().isUnauthorized());
        }
        assertThat(db.queryForObject("SELECT active FROM participants WHERE id=?",Boolean.class,f.pid())).isFalse(); // 다시 들어오기 실패는 활성으로 바꾸지 않음
    }
}
