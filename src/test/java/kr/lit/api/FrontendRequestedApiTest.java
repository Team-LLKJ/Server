package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrontendRequestedApiTest extends AuthTestSupport {
    Map<String,Object> templateInput() {
        var map=new LinkedHashMap<String,Object>();map.put("title","프런트 계약");map.put("guidelines","<p>공통 안내</p>");map.put("aiPolicy","HINT_ONLY");
        map.put("criteria",List.of("근거의 적절성","자기 생각"));map.put("antiCheat",List.of("TAB_LEAVE","PRINT_BLOCK"));
        map.put("questions",List.of(Map.of("position",1,"title","문항","content","문제","minChars",1,"maxChars",99999)));return map;
    }
    JsonNode createTemplate() throws Exception {return ok(body(request(post("/api/teacher/assessments"),teacher),templateInput()));}
    JsonNode add(String id) throws Exception {return ok(body(request(post("/api/teacher/assessments/"+id+"/classes"),teacher),Map.of("className","1반")));}
    JsonNode start(String id,String c) throws Exception {
        var lobby=ok(body(request(post("/api/teacher/assessments/"+id+"/classes/"+c+"/lobby"),teacher),Map.of("expiresInMinutes",60)));
        evaluations.join(lobby.path("pin").asText(),"대기 학생",null);
        return ok(body(request(post("/api/teacher/assessments/"+id+"/classes/"+c+"/start"),teacher),Map.of("expiresInMinutes",40)));}
    @Test void frontendTitleAndAntiCheatArraysRoundTripAndReadyStateIsExplicit() throws Exception {
        var t=createTemplate();assertThat(t.path("criteria").get(0).asText()).isEqualTo("근거의 적절성");assertThat(t.path("antiCheat").get(1).asText()).isEqualTo("PRINT_BLOCK");
        String id=t.path("id").asText();var c=add(id);assertThat(c.path("state").asText()).isEqualTo("READY");assertThat(c.path("evaluationState").asText()).isEqualTo("WAITING");
        var s=start(id,c.path("classId").asText());as(teacher);var p=evaluations.join(s.path("pin").asText(),"학생",null);
        mvc.perform(get("/api/student/evaluations/"+c.path("classId").asText()+"/workspace").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk())
                .andExpect(jsonPath("criteria[0]").value("근거의 적절성")).andExpect(jsonPath("antiCheat[0]").value("TAB_LEAVE"));
        // 제목 선택만으로 임의의100점 배점을 생성하지 않습니다.
        assertThat(db.queryForObject("SELECT COUNT(*) FROM evaluation_rubrics WHERE evaluation_id=?",Integer.class,c.path("classId").asText())).isZero();
    }
    @Test void existingConfigurationAcceptsFrontendArraysToo() throws Exception {
        var e=evaluations.create("기존 설정",60);var v=templateInput();v.remove("title");v.remove("guidelines");
        mvc.perform(body(request(put("/api/teacher/evaluations/"+e.id()+"/configuration"),teacher),v)).andExpect(status().isOk()).andExpect(jsonPath("criteria[1]").value("자기 생각"));
        mvc.perform(request(get("/api/teacher/evaluations/"+e.id()+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("antiCheat[1]").value("PRINT_BLOCK"));
    }
    @Test void fullscreenAndDevtoolsSettingsRoundTripThroughTemplateUpdatesAndWorkspace() throws Exception {
        var input=templateInput();input.put("antiCheat",List.of("FULLSCREEN_LEAVE","DEVTOOLS"));
        var t=ok(body(request(post("/api/teacher/assessments"),teacher),input));String id=t.path("id").asText();
        assertThat(t.path("antiCheat")).isEqualTo(json.valueToTree(input.get("antiCheat")));
        String classId=add(id).path("classId").asText();
        input.put("revision",t.path("revision").asInt());input.put("antiCheat",List.of("TAB_LEAVE","PRINT_BLOCK","FULLSCREEN_LEAVE","DEVTOOLS"));
        mvc.perform(body(request(put("/api/teacher/assessments/"+id),teacher),input)).andExpect(status().isOk())
                .andExpect(jsonPath("antiCheat[2]").value("FULLSCREEN_LEAVE")).andExpect(jsonPath("antiCheat[3]").value("DEVTOOLS"));
        mvc.perform(request(get("/api/teacher/assessments/"+id),teacher)).andExpect(status().isOk()).andExpect(jsonPath("antiCheat[3]").value("DEVTOOLS"));
        mvc.perform(request(get("/api/teacher/evaluations/"+classId+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("antiCheat[2]").value("FULLSCREEN_LEAVE"));
        var started=start(id,classId);as(teacher);var participant=evaluations.join(started.path("pin").asText(),"학생",null);
        mvc.perform(get("/api/student/evaluations/"+classId+"/workspace").header("X-Participant-Token",participant.participantToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("antiCheat[3]").value("DEVTOOLS"));
    }
    @Test void newSettingsAreAcceptedByBothPutAliasesAndInvalidCodesStillFail() throws Exception {
        var e=evaluations.create("전체화면 설정",60);var input=templateInput();input.remove("title");
        input.put("antiCheat",List.of("FULLSCREEN_LEAVE","DEVTOOLS"));
        for(String alias:List.of("questions","configuration")) {
            mvc.perform(body(request(put("/api/teacher/evaluations/"+e.id()+"/"+alias),teacher),input))
                    .andExpect(status().isOk()).andExpect(jsonPath("antiCheat[0]").value("FULLSCREEN_LEAVE"));
            mvc.perform(request(get("/api/teacher/evaluations/"+e.id()+"/"+alias),teacher))
                    .andExpect(status().isOk()).andExpect(jsonPath("antiCheat[1]").value("DEVTOOLS"));
            for(var codes:List.of(List.of("DEVTOOLS","DEVTOOLS"),List.of("FULLSCREEN_EXIT"),List.of("UNKNOWN"))) {
                var invalid=new LinkedHashMap<>(input);invalid.put("antiCheat",codes);
                mvc.perform(body(request(put("/api/teacher/evaluations/"+e.id()+"/"+alias),teacher),invalid))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ANTI_CHEAT"));
            }
        }
        var participant=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        mvc.perform(get("/api/evaluations/"+e.id()+"/questions").header("X-Participant-Token",participant.participantToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("antiCheat[1]").value("DEVTOOLS"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"FULLSCREEN_EXIT","DEVTOOLS_OPEN"})
    void newWarningsShareRateLimitCountInActivityAndNotifyOnlyOnce(String first) throws Exception {
        var e=evaluations.create("경고 합산",60);work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"문항","문제"))));
        var participant=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        String other=first.equals("FULLSCREEN_EXIT")?"DEVTOOLS_OPEN":"FULLSCREEN_EXIT";
        for(int i=0;i<60;i++) {
            String type=i==0?first:i==1?other:i==2?"WINDOW_BLUR":"PASTE";
            mvc.perform(body(post("/api/evaluations/"+e.id()+"/events").header("X-Participant-Token",participant.participantToken()),Map.of("type",type,"occurredAt",Instant.now())))
                    .andExpect(status().isCreated()).andExpect(jsonPath("details.warning").value(i<3));
            if(i==0)mvc.perform(request(get("/api/notifications/unread-count"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("unreadCount").value(1));
        }
        mvc.perform(body(post("/api/student/evaluations/"+e.id()+"/learning-events").header("X-Participant-Token",participant.participantToken()),Map.of("type","DEVTOOLS_OPEN","occurredAt",Instant.now())))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("code").value("EVENT_RATE_LIMITED"));
        String base="/api/teacher/evaluations/"+e.id();
        mvc.perform(request(get(base+"/students"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("items[0].warningCount").value(3));
        mvc.perform(request(get(base+"/submissions"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("items[0].warningCount").value(3));
        mvc.perform(request(get(base+"/students/"+participant.participantId()+"/activity"),teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("warningsTotal").value(3)).andExpect(jsonPath("warnings[0].type").value(first)).andExpect(jsonPath("warnings[1].type").value(other));
        mvc.perform(request(get("/api/notifications/unread-count"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("unreadCount").value(1));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM grading_scores WHERE evaluation_id=?",Integer.class,e.id())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_events WHERE participant_id=? AND event_type IN ("+ClientEventType.clientSql()+")",Integer.class,participant.participantId())).isEqualTo(60);
    }
    @Test void extensionUpdatesStudentDeadlineAndPreservesPinAndExistingAnswers() throws Exception {
        var e=evaluations.create("시간 연장",60);var conf=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","문제"))));
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());work.save(e.id(),p.participantToken(),conf.questions().get(0).id(),new SaveAnswer("기존 답안",0,AnswerSource.DIRECT,null));
        var before=evaluations.lobby(e.id()).endsAt();
        var extended=ok(body(request(post("/api/teacher/evaluations/"+e.id()+"/extend"),teacher),Map.of("additionalMinutes",10)));
        assertThat(Instant.parse(extended.path("endsAt").asText())).isEqualTo(before.plusSeconds(600));
        mvc.perform(get("/api/student/evaluations/"+e.id()+"/state").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk()).andExpect(jsonPath("endsAt").value(extended.path("endsAt").asText()));
        as(teacher);assertThat(evaluations.details(e.id()).pin()).isEqualTo(e.pin());assertThat(work.workspace(e.id(),p.participantToken()).questions().get(0).answer().version()).isEqualTo(1);
    }
    @Test void classExtensionIsVisibleInClassListAndCannotResurrectExpiredClass() throws Exception {
        var t=createTemplate();String id=t.path("id").asText(),c=add(id).path("classId").asText();var s=start(id,c);
        var extended=ok(body(request(post("/api/teacher/evaluations/"+c+"/extend"),teacher),Map.of("additionalMinutes",600)));
        assertThat(Instant.parse(extended.path("endsAt").asText())).isEqualTo(Instant.parse(s.path("endsAt").asText()).plusSeconds(36000));
        mvc.perform(request(get("/api/teacher/assessments/"+id+"/classes"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("$[0].endsAt").value(extended.path("endsAt").asText()));
        db.update("UPDATE evaluations SET ends_at=? WHERE id=?",OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2),c);
        mvc.perform(body(request(post("/api/teacher/evaluations/"+c+"/extend"),teacher),Map.of("additionalMinutes",10))).andExpect(status().isConflict());
    }
    @Test void extensionRejectsWaitingEndedInvalidRangeAndOtherTeacher() throws Exception {
        var e=evaluations.create("연장 검사",60);String url="/api/teacher/evaluations/"+e.id()+"/extend";
        mvc.perform(body(request(post(url),teacher),Map.of("additionalMinutes",10))).andExpect(status().isConflict());
        for(int n:List.of(0,601,-1))mvc.perform(body(request(post(url),teacher),Map.of("additionalMinutes",n))).andExpect(status().isBadRequest());
        mvc.perform(body(request(post(url),newTeacher()),Map.of("additionalMinutes",10))).andExpect(status().isNotFound());
        as(teacher);evaluations.end(e.id());mvc.perform(body(request(post(url),teacher),Map.of("additionalMinutes",10))).andExpect(status().isConflict());
    }
    @Test void studentsAndSubmissionsExposeWarningCountsAndLastReceivedEventTime() throws Exception {
        var t=createTemplate();String id=t.path("id").asText(),c=add(id).path("classId").asText();
        String root="/api/teacher/assessments/"+id+"/classes/"+c;
        var lobby=ok(body(request(post(root+"/lobby"),teacher),Map.of("expiresInMinutes",60)));
        var p=evaluations.join(lobby.path("pin").asText(),"학생",null);
        ok(body(request(post(root+"/start"),teacher),Map.of("expiresInMinutes",40)));
        var occurred=Instant.now().minusSeconds(60);
        mvc.perform(body(post("/api/student/evaluations/"+c+"/events").header("X-Participant-Token",p.participantToken()),Map.of("type","WINDOW_LEAVE","occurredAt",occurred))).andExpect(status().isCreated());
        mvc.perform(body(post("/api/student/evaluations/"+c+"/events").header("X-Participant-Token",p.participantToken()),Map.of("type","SHORTCUT_CAPTURE","occurredAt",occurred))).andExpect(status().isCreated());
        var students=ok(request(get("/api/teacher/evaluations/"+c+"/students"),teacher));var row=students.path("items").get(0);
        assertThat(row.path("warningCount").asInt()).isEqualTo(2);assertThat(Instant.parse(row.path("lastEventAt").asText())).isAfter(occurred);
        var submissions=ok(request(get("/api/teacher/evaluations/"+c+"/submissions"),teacher));
        assertThat(submissions.path("items").get(0).path("warningCount").asInt()).isEqualTo(2);assertThat(submissions.path("items").get(0).path("lastEventAt").asText()).isEqualTo(row.path("lastEventAt").asText());
        mvc.perform(request(get("/api/notifications/unread-count"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("unreadCount").value(1));
    }
    @Test void selectionArraysRejectBlankDuplicateMixedAndUnsupportedCodes() throws Exception {
        for(var criteria:List.of(List.of(""),List.of("중복","중복"),List.of("제목",Map.of("name","상세","maxScore",100)))) {
            var v=templateInput();v.put("criteria",criteria);
            mvc.perform(body(request(post("/api/teacher/assessments"),teacher),v)).andExpect(status().isBadRequest());
        }
        for(var anti:List.of(List.of("TAB_LEAVE","TAB_LEAVE"),List.of("UNKNOWN"))) {
            var v=templateInput();v.put("antiCheat",anti);
            mvc.perform(body(request(post("/api/teacher/assessments"),teacher),v)).andExpect(status().isBadRequest());
        }
    }
    @Test void explicitCharacterLimitsMustBeOneTo99999ButMayBeOmitted() throws Exception {
        for(int limit:List.of(0,100000,-1)) {
            var v=templateInput();v.put("questions",List.of(Map.of("position",1,"title","q","content","c","minChars",limit)));
            mvc.perform(body(request(post("/api/teacher/assessments"),teacher),v)).andExpect(status().isBadRequest());
            v.put("questions",List.of(Map.of("position",1,"title","q","content","c","maxChars",limit)));
            mvc.perform(body(request(post("/api/teacher/assessments"),teacher),v)).andExpect(status().isBadRequest());
        }
        var v=templateInput();v.put("questions",List.of(Map.of("position",1,"title","q","content","c")));
        var t=ok(body(request(post("/api/teacher/assessments"),teacher),v));assertThat(t.path("questions").get(0).path("minChars").isNull()).isTrue();
    }
    @Test void titleSelectionsStayIndependentOfRubricEditsAndExplicitEmptySelectionsRemainEmpty() throws Exception {
        var e=evaluations.create("선택 항목",60);String root="/api/teacher/evaluations/"+e.id();var v=templateInput();v.remove("title");v.remove("guidelines");
        ok(body(request(put(root+"/configuration"),teacher),v));
        ok(body(request(put(root+"/rubric"),teacher),Map.of("criteria",List.of(Map.of("name","직접 채점","maxScore",100)))));
        mvc.perform(request(get(root+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("criteria[0]").value("근거의 적절성"));
        v.put("criteria",List.of());ok(body(request(put(root+"/configuration"),teacher),v));
        mvc.perform(request(get(root+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("criteria").isEmpty());
        mvc.perform(request(get(root+"/rubric"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("criteria[0].name").value("직접 채점"));
    }

}
