package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BackendIntegrationCompletionApiTest extends AuthTestSupport {
    @Autowired ReportService reports;
    @Autowired ReportPdf pdf;
    @Autowired TemplateService templates;
    record Fixture(String id,String pid,String token,String qid) {}
    Fixture fixture(boolean started){as(teacher);var e=evaluations.create("통합 보고서",60,"안내 원문");var q=work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"문항","근거를 설명하세요.")))).questions().get(0).id();var p=evaluations.join(e.pin(),"학생",null);if(started)evaluations.start(e.id());return new Fixture(e.id(),p.participantId(),p.participantToken(),q);}
    void submit(Fixture f){work.save(f.id(),f.token(),f.qid(),new SaveAnswer("학생의 최종 답안입니다.",0,AnswerSource.DIRECT,null));var r=work.review(f.id(),f.token());work.submit(f.id(),f.token(),r.reviewToken());}
    JsonNode me(Fixture f)throws Exception{return ok(get("/api/evaluations/"+f.id()+"/me").header("X-Participant-Token",f.token()));}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder accept(Fixture f,String version){return post("/api/evaluations/"+f.id()+"/policy-acceptance").header("X-Participant-Token",f.token()).contentType("application/json").content("{\"policyVersion\":\""+version+"\"}");}
    @Test void evaluationCreationPersistsSettingsAndRejectsInvalidCodesAtomically()throws Exception {
        var created=ok(body(request(post("/api/teacher/evaluations"),teacher),Map.of("title","설정 포함","expiresInMinutes",60,"criteria",List.of("비판적 검토"),"antiCheat",List.of("FULLSCREEN","DEVTOOLS"))));
        as(teacher);var configuration=work.configuration(created.path("id").asText());assertThat(configuration.criteria()).isEqualTo(List.of("비판적 검토"));assertThat(configuration.antiCheat().toString()).isEqualTo("[\"FULLSCREEN_LEAVE\",\"DEVTOOLS\"]");
        int count=db.queryForObject("SELECT COUNT(*) FROM evaluations",Integer.class);
        mvc.perform(body(request(post("/api/teacher/evaluations"),teacher),Map.of("title","잘못된 설정","expiresInMinutes",60,"antiCheat",List.of("UNKNOWN")))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ANTI_CHEAT"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM evaluations",Integer.class)).isEqualTo(count);
    }
    @Test void sessionAndPolicyAcceptanceUseServerStateAndInvalidateChangedGuidelines()throws Exception {
        var f=fixture(false);var before=me(f);String first=before.at("/policy/policyVersion").asText();assertThat(before.at("/policy/policyAccepted").asBoolean()).isFalse();assertThat(before.path("status").asText()).isEqualTo("WAITING");assertThat(before.has("participantToken")).isFalse();
        var accepted=ok(accept(f,first));var repeated=ok(accept(f,first));assertThat(repeated.path("policyAcceptedAt")).isEqualTo(accepted.path("policyAcceptedAt"));
        db.update("UPDATE evaluations SET evaluation_content='바뀐 평가 안내' WHERE id=?",f.id());assertThat(me(f).at("/policy/policyAccepted").asBoolean()).isFalse();
        mvc.perform(accept(f,first)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("POLICY_VERSION_CONFLICT"));
        String second=me(f).at("/policy/policyVersion").asText();ok(accept(f,second));assertThat(db.queryForObject("SELECT COUNT(*) FROM participant_policy_acceptances WHERE participant_id=?",Integer.class,f.pid())).isEqualTo(2);
        as(teacher);evaluations.start(f.id());mvc.perform(get("/api/student/evaluations/"+f.id()+"/workspace").header("X-Participant-Token",f.token())).andExpect(jsonPath("policyAccepted").value(true)).andExpect(jsonPath("policyVersion").value(second));
        var foreign=fixture(false);mvc.perform(get("/api/evaluations/"+f.id()+"/me").header("X-Participant-Token",foreign.token())).andExpect(status().isUnauthorized());
        as(teacher);evaluations.delete(foreign.id());
    }
    @Test void eventAliasesDisclosureAndConversationIdsSurviveSubmissionAndExport()throws Exception {
        var f=fixture(true);work.recordClientEvent(f.id(),f.token(),new ClientEvent(ClientEventType.FULLSCREEN,java.time.Instant.now(),null));work.recordClientEvent(f.id(),f.token(),new ClientEvent(ClientEventType.DEVTOOLS,java.time.Instant.now(),null));
        var turn=work.reserve(f.id(),f.token(),f.qid(),new Ask("integration-turn","개념을 설명해줘"),"stub","test");work.finish(turn,"개념 설명",null);
        mvc.perform(put("/api/student/evaluations/"+f.id()+"/ai-disclosure").header("X-Participant-Token",f.token()).contentType("application/json").content("{\"tools\":\"LIT\",\"usedParts\":\"최종 답안\",\"reason\":\"저장설명동결표식\"}")).andExpect(status().isOk());submit(f);
        as(teacher);String root="/api/teacher/evaluations/"+f.id()+"/students/"+f.pid();var messages=ok(request(get(root+"/conversations"),teacher));assertThat(messages.get(0).path("conversationId").asLong()).isEqualTo(turn.turn().id());assertThat(messages.get(0).path("id").asText()).isEqualTo(turn.turn().id()+"-USER");
        var activity=ok(request(get(root+"/activity"),teacher));assertThat(activity.path("warningsTotal").asInt()).isEqualTo(2);assertThat(activity.path("studentContribution").isNull()).isTrue();
        db.update("UPDATE ai_disclosures SET reason='제출 후 강제 변경' WHERE participant_id=?",f.pid());mvc.perform(request(get(root+"/ai-disclosure"),teacher)).andExpect(jsonPath("source").value("SYSTEM_GENERATED")).andExpect(jsonPath("status").value("READY")).andExpect(jsonPath("frozen").value(true));
        // HWP first GET generates the missing report; repeated GET reuses it.
        mvc.perform(request(get(root+"/report/hwp"),teacher)).andExpect(status().isOk());mvc.perform(request(get(root+"/report/hwp"),teacher)).andExpect(status().isOk());as(teacher);assertThat(reports.versions(f.id(),f.pid())).hasSize(1);
        var report=reports.teacherReport(f.id(),f.pid(),null);try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf.render(report,false))){assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc)).contains("AI 활용 기록").doesNotContain("제출 후 강제 변경","저장설명동결표식");}
        assertThat(me(f).path("readOnly").asBoolean()).isTrue();mvc.perform(accept(f,me(f).at("/policy/policyVersion").asText())).andExpect(status().isConflict());
    }
    Map<String,byte[]> download(String path)throws Exception {
        var response=mvc.perform(request(get(path),teacher)).andExpect(status().isOk()).andExpect(content().contentType("application/zip")).andReturn().getResponse().getContentAsByteArray();
        var entries=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(response),StandardCharsets.UTF_8)){for(ZipEntry e;(e=zip.getNextEntry())!=null;)entries.put(e.getName(),zip.readAllBytes());}return entries;
    }
    @Test void classArchiveGeneratesMissingReportsIncludesManifestAndRejectsOtherTeachers()throws Exception {
        var f=fixture(false);as(teacher);var e=evaluations.details(f.id());evaluations.join(e.pin(),"미제출 학생",null);evaluations.start(f.id());submit(f);as(teacher);
        // Existing participant with no submission appears as skipped rather than failing the ZIP.
        var o=fixture(false); // separate scope must never appear in this archive
        var entries=download("/api/teacher/evaluations/"+f.id()+"/reports/archive?format=HWP");assertThat(entries).containsKey("manifest.json");assertThat(entries.keySet().stream().filter(n->n.endsWith(".hwp"))).hasSize(1);assertThat(entries.keySet()).noneMatch(n->n.contains(".."));
        var manifest=json.readTree(entries.get("manifest.json"));assertThat(manifest.path("items")).hasSize(2);var statuses=new ArrayList<String>();manifest.path("items").forEach(item->statuses.add(item.path("status").asText()));assertThat(statuses).containsExactlyInAnyOrder("DONE","SKIPPED");
        as(teacher);assertThat(reports.versions(f.id(),f.pid())).hasSize(1);download("/api/teacher/evaluations/"+f.id()+"/reports/archive?format=PDF");as(teacher);assertThat(reports.versions(f.id(),f.pid())).hasSize(1);
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/reports/archive"),newTeacher())).andExpect(status().isNotFound());
    }
    @Test void folderArchiveGroupsClassesAndChecksAssessmentScope()throws Exception {
        as(teacher);var template=templates.create(new TemplateModels.Input("폴더 평가","안내",AiPolicy.HINT_ONLY,List.of(),null,List.of(new QuestionInput(1,"문항","문제")),60));
        for(String name:List.of("1반","2반")){var room=templates.addClass(template.id(),name);var lobby=templates.openLobby(template.id(),room.classId(),60);var student=evaluations.join(lobby.pin(),"같은 닉네임",null);templates.start(template.id(),room.classId(),60);String q=work.configuration(room.evaluationId()).questions().get(0).id();submit(new Fixture(room.evaluationId(),student.participantId(),student.participantToken(),q));as(teacher);}
        var files=download("/api/teacher/folders/"+template.id()+"/reports/archive?format=PDF");assertThat(files.keySet().stream().filter(n->n.endsWith(".pdf"))).hasSize(2);assertThat(files.keySet().stream().filter(n->n.endsWith(".pdf")).map(n->n.split("/")[0]).distinct()).hasSize(2);
        mvc.perform(request(get("/api/teacher/assessments/"+template.id()+"/reports/archive"),newTeacher())).andExpect(status().isNotFound());
    }
}
