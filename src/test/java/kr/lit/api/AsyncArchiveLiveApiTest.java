package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AsyncArchiveLiveApiTest extends AuthTestSupport {
    @Autowired ReportArchiveJobService jobs;
    @Autowired ReportArchiveJobRepository repository;
    @Autowired ClassEventService events;
    record Fixture(String id,String pid,String token) {}
    Fixture fixture(){as(teacher);var e=evaluations.create("실시간·ZIP",60);work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"문항","문제"))));var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());return new Fixture(e.id(),p.participantId(),p.participantToken());}
    @Test void asynchronousArchiveStatusDownloadAuthorizationAndExpiry()throws Exception {
        var f=fixture();var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());
        var created=ok(request(post("/api/teacher/evaluations/"+f.id()+"/report-archive-jobs?format=HWP"),teacher));String id=created.path("id").asText();assertThat(created.path("status").asText()).isEqualTo("PENDING");jobs.processJob(id);
        var status=ok(request(get("/api/teacher/report-archive-jobs/"+id),teacher));assertThat(status.path("status").asText()).isEqualTo("DONE");assertThat(status.path("downloadUrl").asText()).endsWith("/download");
        mvc.perform(request(get(status.path("downloadUrl").asText()),teacher)).andExpect(status().isOk()).andExpect(content().contentType("application/zip"));
        mvc.perform(request(get("/api/teacher/report-archive-jobs/"+id),newTeacher())).andExpect(status().isNotFound());
        String path=repository.getJob(id).orElseThrow().path();db.update("UPDATE report_archive_jobs SET completed_at=DATEADD('HOUR',-2,CURRENT_TIMESTAMP) WHERE id=?",id);
        mvc.perform(request(get("/api/teacher/report-archive-jobs/"+id),teacher)).andExpect(jsonPath("status").value("EXPIRED")).andExpect(jsonPath("downloadUrl").isEmpty());
        mvc.perform(request(get("/api/teacher/report-archive-jobs/"+id+"/download"),teacher)).andExpect(status().isGone());jobs.processPending();assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(path))).isFalse();
    }
    @Test void liveEventsReplaySubmissionAndWarningWithStableCursorAndRequireTeacherScope()throws Exception {
        var f=fixture();String path="/api/teacher/evaluations/"+f.id()+"/live-events";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(request(get(path),newTeacher())).andExpect(status().isNotFound());
        var pending=mvc.perform(request(get(path),teacher)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        work.recordClientEvent(f.id(),f.token(),new ClientEvent(ClientEventType.WINDOW_LEAVE,Instant.now(),null));var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());events.publishEvents();
        String data=pending.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertThat(data).contains("event:warning","event:submission","participantId",f.pid(),"id:");
        db.update("UPDATE auth_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=?",teacher.user().id());events.publishEvents();
    }
    @Test void allowedFrontendCanSendReplayCursorInCorsPreflight()throws Exception {
        mvc.perform(options("/api/teacher/evaluations/example/live-events").header("Origin","https://frontend.example.test").header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","Authorization,Last-Event-ID"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://frontend.example.test")).andExpect(header().string("Access-Control-Allow-Headers",org.hamcrest.Matchers.containsString("Last-Event-ID")));
    }
}
