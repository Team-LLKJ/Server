package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class FrontendContractApiTest extends AuthTestSupport {
    record Fixture(String id,String participant,String token,String question) {}
    Fixture fixture(boolean started) {
        as(teacher);var e=evaluations.create("프런트 연동",60,"기존 안내\n둘째 줄");
        var configuration=work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"문항","설명하세요."))));
        var joined=evaluations.join(e.pin(),"학생",null);
        if(started)evaluations.start(e.id());
        return new Fixture(e.id(),joined.participantId(),joined.participantToken(),configuration.questions().get(0).id());
    }
    String root(Fixture f) {return "/api/student/evaluations/"+f.id();}
    Object config(String text) {return Map.of("guidelines",text,"aiPolicy","DISABLED","questions",List.of(Map.of("position",1,"title","문항","content","설명")));}
    @Test void migratesOldAnswerColumnWithoutLosingSavedData() throws Exception {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:legacy_answers;DB_CLOSE_DELAY=-1","sa","");
        var schema=new org.springframework.core.io.ClassPathResource("schema.sql");
        String oldSchema=new String(schema.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("ALTER TABLE answers ALTER COLUMN content CLOB;","");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(oldSchema.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(source);
        var old=new org.springframework.jdbc.core.JdbcTemplate(source);
        old.update("INSERT INTO evaluations(id,title,pin,state,expires_at) VALUES ('e','기존','000555','WAITING',CURRENT_TIMESTAMP)");
        old.update("INSERT INTO participants(id,evaluation_id,nickname,token,active,last_seen) VALUES ('p','e','기존 학생','token',TRUE,CURRENT_TIMESTAMP)");
        old.update("INSERT INTO questions(id,evaluation_id,position,title,content) VALUES ('q','e',1,'기존 문항','본문')");
        old.update("INSERT INTO answers VALUES ('p','q','기존 답안',3,CURRENT_TIMESTAMP)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(schema).execute(source);
        assertThat(old.queryForObject("SELECT content FROM answers WHERE participant_id='p'",String.class)).isEqualTo("기존 답안");
        assertThat(old.queryForObject("SELECT version FROM answers WHERE participant_id='p'",Integer.class)).isEqualTo(3);
        old.update("UPDATE answers SET content=? WHERE participant_id='p'","가".repeat(100000));
        assertThat(old.queryForObject("SELECT LENGTH(content) FROM answers WHERE participant_id='p'",Integer.class)).isEqualTo(100000);
    }
    @Test void guidelinesCreateConfigurationLegacyContentAndWorkspaceStayInSync() throws Exception {
        var created=ok(body(request(post("/api/teacher/evaluations"),teacher),Map.of("title","생성 안내","expiresInMinutes",60,"guidelines","생성 안내\n그대로")));
        String createdId=created.path("id").asText();
        mvc.perform(request(get("/api/teacher/evaluations/"+createdId+"/content"),teacher)).andExpect(jsonPath("evaluationContent").value("생성 안내\n그대로"));
        var f=fixture(false);String path="/api/teacher/evaluations/"+f.id()+"/configuration";
        mvc.perform(request(get(path),teacher)).andExpect(status().isOk()).andExpect(jsonPath("guidelines").value("기존 안내\n둘째 줄"));
        mvc.perform(body(request(put(path),teacher),config("새 안내\n표를 사용하세요."))).andExpect(status().isOk()).andExpect(jsonPath("guidelines").value("새 안내\n표를 사용하세요."));
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/content"),teacher)).andExpect(jsonPath("evaluationContent").value("새 안내\n표를 사용하세요."));
        as(teacher);evaluations.start(f.id());
        mvc.perform(get(root(f)+"/workspace").header("X-Participant-Token",f.token())).andExpect(status().isOk())
                .andExpect(jsonPath("guidelines").value("새 안내\n표를 사용하세요.")).andExpect(jsonPath("evaluationContent").value("새 안내\n표를 사용하세요."));
        mvc.perform(body(request(put(path),teacher),config("시작 후 변경"))).andExpect(status().isConflict());
    }
    @Test void configurationOmissionPreservesGuidelinesAndOwnershipIsEnforced() throws Exception {
        var f=fixture(false);String path="/api/teacher/evaluations/"+f.id()+"/configuration";
        var omitted=new HashMap<>((Map<String,Object>)config(""));omitted.remove("guidelines");
        mvc.perform(body(request(put(path),teacher),omitted)).andExpect(status().isOk()).andExpect(jsonPath("guidelines").value("기존 안내\n둘째 줄"));
        var other=newTeacher();
        mvc.perform(request(get(path),other)).andExpect(status().isNotFound());
        mvc.perform(get(root(f)+"/workspace")).andExpect(status().isUnauthorized());
        mvc.perform(body(request(post("/api/teacher/evaluations"),teacher),Map.of("title","충돌","expiresInMinutes",60,"guidelines","새 값","evaluationContent","다른 값"))).andExpect(status().isBadRequest());
    }
    @Test void recordsClientEventsOnTeacherTimelineWithoutAffectingAnswerVersion() throws Exception {
        var f=fixture(true);String when=Instant.now().toString();
        for(String type:List.of("WINDOW_LEAVE","SHORTCUT_CAPTURE")) {
            mvc.perform(body(post(root(f)+"/events").header("X-Participant-Token",f.token()),Map.of("type",type,"occurredAt",when,"questionId",f.question())))
                    .andExpect(status().isCreated()).andExpect(jsonPath("type").value(type)).andExpect(jsonPath("details.occurredAt").value(when))
                    .andExpect(jsonPath("details.clientReported").value(true)).andExpect(jsonPath("createdAt").isNotEmpty());
        }
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/participants/"+f.participant()+"/learning-events"),teacher))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].type").value("WINDOW_LEAVE")).andExpect(jsonPath("$[1].type").value("SHORTCUT_CAPTURE"));
        assertThat(work.select(f.id(),f.token(),f.question()).answer().version()).isZero();
    }
    @Test void rejectsForgedEventTypesWrongRoomTimesAndClosedWork() throws Exception {
        var f=fixture(true);var other=fixture(true);
        mvc.perform(body(post(root(f)+"/events").header("X-Participant-Token",other.token()),Map.of("type","WINDOW_LEAVE","occurredAt",Instant.now().toString())))
                .andExpect(status().isUnauthorized());
        mvc.perform(body(post(root(f)+"/events").header("X-Participant-Token",f.token()),Map.of("type","AI_COMPLETED","occurredAt",Instant.now().toString())))
                .andExpect(status().isBadRequest());
        mvc.perform(body(post(root(f)+"/events").header("X-Participant-Token",f.token()),Map.of("type","WINDOW_LEAVE","occurredAt",Instant.now().plusSeconds(600).toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_EVENT_TIME"));
        mvc.perform(body(post(root(f)+"/events").header("X-Participant-Token",f.token()),Map.of("type","WINDOW_LEAVE","occurredAt",Instant.now().toString(),"questionId",other.question())))
                .andExpect(status().isNotFound());
        as(teacher);evaluations.end(f.id());
        mvc.perform(body(post(root(f)+"/events").header("X-Participant-Token",f.token()),Map.of("type","WINDOW_LEAVE","occurredAt",Instant.now().toString())))
                .andExpect(status().isConflict());
    }
    @Test void tableOnlyAnswerIsSavedAndCountedAsNonBlank() throws Exception {
        var f=fixture(true);
        String table="<table><colgroup><col colwidth=\"120\"></colgroup><thead><tr><th colspan=\"2\">제목</th></tr></thead><tbody><tr><td rowspan=\"2\" colwidth=\"120\"></td><td></td></tr></tbody></table>";
        var response=ok(body(put(root(f)+"/questions/"+f.question()+"/answer").header("X-Participant-Token",f.token()),Map.of("content",table,"expectedVersion",0,"source","DIRECT")));
        String saved=response.path("content").asText();
        assertThat(saved).contains("<table>","<thead>","<tbody>","<tr>","<th colspan=\"2\">","<colgroup>","colwidth=\"120\"","rowspan=\"2\"");
        String emptyTable="<table><tbody><tr><td></td></tr></tbody></table>";
        work.save(f.id(),f.token(),f.question(),new SaveAnswer(emptyTable,1,AnswerSource.DIRECT,null));
        assertThat(work.review(f.id(),f.token()).blankQuestionIds()).doesNotContain(f.question());
        assertThat(db.queryForObject("SELECT content FROM answers WHERE participant_id=?",String.class,f.participant())).contains("<table>");
    }
    @Test void acceptsOneMiBAndRepeatedAutosavesPreservesVersionConflicts() throws Exception {
        var f=fixture(true);
        String prefix="<table><tbody><tr><td>",suffix="</td></tr></tbody></table>";
        String full=prefix+"x".repeat(AnswerHtml.MAX_BYTES-prefix.length()-suffix.length())+suffix;
        mvc.perform(body(put(root(f)+"/questions/"+f.question()+"/answer").header("X-Participant-Token",f.token()),Map.of("content",full,"expectedVersion",0,"source","DIRECT")))
                .andExpect(status().isOk()).andExpect(jsonPath("version").value(1));
        assertThat(work.select(f.id(),f.token(),f.question()).answer().content()).hasSize(AnswerHtml.MAX_BYTES);
        mvc.perform(body(put(root(f)+"/questions/"+f.question()+"/answer").header("X-Participant-Token",f.token()),Map.of("content","가".repeat(349526),"expectedVersion",1,"source","DIRECT")))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("code").value("ANSWER_TOO_LARGE"));
        for(int version=1;version<=35;version++) {
            mvc.perform(body(put(root(f)+"/questions/"+f.question()+"/answer").header("X-Participant-Token",f.token()),Map.of("content","자동 저장 "+version,"expectedVersion",version,"source","DIRECT")))
                    .andExpect(status().isOk());
        }
        mvc.perform(body(put(root(f)+"/questions/"+f.question()+"/answer").header("X-Participant-Token",f.token()),Map.of("content","충돌","expectedVersion",0,"source","DIRECT")))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("ANSWER_VERSION_CONFLICT"));
    }
    @Test void eventLimitsDoNotPreventAutosaveAndSubmissionClosesEvents() throws Exception {
        var f=fixture(true);
        for(int i=0;i<60;i++)work.recordClientEvent(f.id(),f.token(),new ClientEvent(ClientEventType.WINDOW_LEAVE,Instant.now(),null));
        mvc.perform(body(post(root(f)+"/events").header("X-Participant-Token",f.token()),Map.of("type","WINDOW_LEAVE","occurredAt",Instant.now().toString())))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("code").value("EVENT_RATE_LIMITED"));
        work.save(f.id(),f.token(),f.question(),new SaveAnswer("저장 가능",0,AnswerSource.DIRECT,null));
        var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());
        mvc.perform(body(post(root(f)+"/events").header("X-Participant-Token",f.token()),Map.of("type","WINDOW_LEAVE","occurredAt",Instant.now().toString())))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("ALREADY_SUBMITTED"));
    }
    @Test void sanitizesExecutableMarkupAndRetainsLegacyRasterImages() {
        String safe=AnswerHtml.clean("<table onclick='bad()'><tr><td colspan='2'>표<script>alert(1)</script><img src='data:image/png;base64,aGVsbG8=' onerror='bad()'><a href='javascript:bad()'>링크</a></td></tr></table>");
        assertThat(safe).contains("<table>","colspan=\"2\"","data:image/png;base64,aGVsbG8=").doesNotContain("onclick","script","onerror","javascript:");
        assertThat(AnswerHtml.clean("<p><img src='data:image/svg+xml;base64,abc='></p>")).doesNotContain("<img");
        assertThat(AnswerHtml.clean("2 < 3\n가나다")).isEqualTo("2 < 3\n가나다");
    }
}
