package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.*;
import static kr.lit.api.TemplateModels.*;
import static kr.lit.api.WorkModels.*;
import static kr.lit.api.AssessmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TemplateApiTest extends AuthTestSupport {
    @Autowired TemplateService templates;
    @Autowired AssessmentService grading;
    Input input(String title,int min,Integer max) {
        return new Input(title,"<h2><strong>공통 안내</strong></h2><script>bad()</script>",AiPolicy.HINT_ONLY,
                List.of(new CriterionInput("근거 제시",100,"설명",null,Indicator.MANUAL)),
                json.valueToTree(Map.of("windowLeave",true,"captureShortcut",false,"custom",Map.of("enabled",true))),
                List.of(new QuestionInput(1,"질문","근거를 설명하세요.",min==0?null:min,max)),60);
    }
    View createTemplate() throws Exception {
        var n=ok(body(request(post("/api/teacher/assessments"),teacher),input("공통 수행평가",0,100)));
        as(teacher);return templates.detail(n.path("id").asText());
    }
    String add(String id,String name) throws Exception {
        return ok(body(request(post("/api/teacher/assessments/"+id+"/classes"),teacher),Map.of("className",name))).path("classId").asText();
    }
    ClassView startClass(String id,String c) {as(teacher);templates.openLobby(id,c,60);return templates.start(id,c,60);}
    @Test void classLobbyWaitsForExplicitStartAndExistingStudentApisKeepWorking() throws Exception {
        var t=createTemplate();String c=add(t.id(),"1반");
        as(teacher);var waiting=templates.classes(t.id()).get(0);
        assertThat(waiting.state()).isEqualTo("READY");assertThat(waiting.pin()).isNull();assertThat(waiting.expiresAt()).isNull();assertThat(waiting.qrUrl()).isNull();
        mvc.perform(request(get("/api/teacher/evaluations/"+c+"/qr"),teacher)).andExpect(status().isConflict());
        String root="/api/teacher/assessments/"+t.id()+"/classes/"+c;
        mvc.perform(body(request(post(root+"/start"),teacher),Map.of("expiresInMinutes",45)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("CLASS_LOBBY_REQUIRED"));
        var lobby=ok(body(request(post(root+"/lobby"),teacher),Map.of("expiresInMinutes",60)));
        String pin=lobby.path("pin").asText();assertThat(pin).matches("[0-9]{6}");
        assertThat(lobby.path("state").asText()).isEqualTo("WAITING");assertThat(lobby.path("endsAt").isNull()).isTrue();
        assertThat(db.queryForObject("SELECT started_at FROM evaluations WHERE id=?",java.time.OffsetDateTime.class,c)).isNull();
        assertThat(ok(body(request(post(root+"/lobby"),teacher),Map.of("expiresInMinutes",30)))).isEqualTo(lobby);
        mvc.perform(get("/api/participation/verify").param("pin",pin)).andExpect(status().isOk())
                .andExpect(jsonPath("id").value(c)).andExpect(jsonPath("pin").value(pin));
        mvc.perform(request(get(lobby.path("qrUrl").asText()),teacher)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        var p=ok(body(request(post("/api/participation/join"),null),Map.of("pin",pin,"nickname","학생")));
        String token=p.path("participantToken").asText();
        assertThat(p.at("/lobby/screen").asText()).isEqualTo("WAITING");
        mvc.perform(get("/api/student/evaluations/"+c+"/workspace").header("X-Participant-Token",token)).andExpect(status().isConflict());
        mvc.perform(get("/api/student/evaluations/"+c+"/state").header("X-Participant-Token",token)).andExpect(jsonPath("screen").value("WAITING"));
        mvc.perform(request(get("/api/teacher/evaluations/"+c+"/participants"),teacher)).andExpect(jsonPath("participants[0].nickname").value("학생"));
        var started=ok(body(request(post(root+"/start"),teacher),Map.of("expiresInMinutes",45)));
        assertThat(started.path("pin").asText()).isEqualTo(pin);assertThat(started.path("state").asText()).isEqualTo("STARTED");
        var began=db.queryForObject("SELECT started_at FROM evaluations WHERE id=?",java.time.OffsetDateTime.class,c);
        var ends=db.queryForObject("SELECT ends_at FROM evaluations WHERE id=?",java.time.OffsetDateTime.class,c);
        assertThat(java.time.Duration.between(began,ends).toMinutes()).isEqualTo(45);
        mvc.perform(body(request(post(root+"/lobby"),teacher),Map.of("expiresInMinutes",60))).andExpect(status().isConflict());
        mvc.perform(get("/api/student/evaluations/"+c+"/workspace").header("X-Participant-Token",token)).andExpect(status().isOk())
                .andExpect(jsonPath("assessmentId").value(t.id())).andExpect(jsonPath("className").value("1반"))
                .andExpect(jsonPath("questions[0].question.maxChars").value(100)).andExpect(jsonPath("criteria[0].maxScore").value(100))
                .andExpect(jsonPath("antiCheat.captureShortcut").value(false));
        mvc.perform(get("/api/student/evaluations/"+c+"/state").header("X-Participant-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("state").value("STARTED"));
        mvc.perform(body(request(post("/api/teacher/assessments/"+t.id()+"/classes/"+c+"/start"),teacher),Map.of("expiresInMinutes",20))).andExpect(status().isConflict());
        as(teacher);assertThat(templates.classes(t.id()).get(0).pin()).isEqualTo(pin);
    }
    @Test void lobbyPermissionsExpiryAndReentryDoNotResetParticipantsOrTimer() throws Exception {
        var t=createTemplate();String c=add(t.id(),"대기반");String root="/api/teacher/assessments/"+t.id()+"/classes/"+c;
        var outsider=newTeacher();
        mvc.perform(body(request(post(root+"/lobby"),outsider),Map.of("expiresInMinutes",60))).andExpect(status().isNotFound());
        mvc.perform(body(request(post(root+"/lobby"),teacher),Map.of("expiresInMinutes",0))).andExpect(status().isBadRequest());
        as(teacher);var lobby=templates.openLobby(t.id(),c,60);var participant=evaluations.join(lobby.pin(),"복귀 학생",null);
        evaluations.participant(c,participant.participantToken(),true);
        var joined=evaluations.join(lobby.pin(),null,participant.participantToken());assertThat(joined.participantId()).isEqualTo(participant.participantId());
        assertThat(joined.lobby().screen()).isEqualTo("WAITING");
        mvc.perform(request(post(root+"/end"),teacher)).andExpect(status().isConflict());
        db.update("UPDATE evaluations SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",c);
        mvc.perform(body(request(post(root+"/start"),teacher),Map.of("expiresInMinutes",45))).andExpect(status().isGone());
        as(teacher);var reopened=templates.openLobby(t.id(),c,60);assertThat(reopened.pin()).isEqualTo(lobby.pin());assertThat(reopened.participantCount()).isEqualTo(1);
        assertThat(reopened.endsAt()).isNull();templates.start(t.id(),c,45);
        templates.end(t.id(),c);
        mvc.perform(body(request(post(root+"/lobby"),teacher),Map.of("expiresInMinutes",60))).andExpect(status().isConflict());
    }
    @Test void concurrentLobbyRequestsKeepOnePinAndStartOnlyOnce() throws Exception {
        var t=createTemplate();String c=add(t.id(),"동시 대기반");var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        Callable<ClassView> open=()->{as(teacher);try{gate.await();return templates.openLobby(t.id(),c,60);}finally{clear();}};
        try {
            var one=pool.submit(open);var two=pool.submit(open);gate.countDown();
            assertThat(one.get(10,TimeUnit.SECONDS).pin()).isEqualTo(two.get(10,TimeUnit.SECONDS).pin());
            Callable<Boolean> start=()->{as(teacher);try{templates.start(t.id(),c,45);return true;}catch(ApiError.Failure failure){return false;}finally{clear();}};
            var first=pool.submit(start);var second=pool.submit(start);
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }finally{pool.shutdownNow();}
    }
    @Test void templateEditUpdatesOnlyWaitingSnapshotsAndPreservesStartedQuestionIds() throws Exception {
        var t=createTemplate();String first=add(t.id(),"1반"),second=add(t.id(),"2반");startClass(t.id(),first);
        var before=work.configuration(first);String q=before.questions().get(0).id();
        var p=evaluations.join(templates.classes(t.id()).stream().filter(c->c.classId().equals(first)).findFirst().orElseThrow().pin(),"첫 학생",null);
        work.save(first,p.participantToken(),q,new SaveAnswer("원래 답안",0,AnswerSource.DIRECT,null));
        var changed=new Input("새 제목","<p>새 안내</p>",AiPolicy.FULL,List.of(new CriterionInput("새 기준",100,null,null,Indicator.PURPOSE)),
                json.valueToTree(Map.of("windowLeave",false)),List.of(new QuestionInput(1,"새 문항","새 내용",4,50)),90);
        ok(body(request(put("/api/teacher/assessments/"+t.id()),teacher),changed));as(teacher);
        var old=work.configuration(first);var fresh=work.configuration(second);
        assertThat(old.questions().get(0).id()).isEqualTo(q);assertThat(old.aiPolicy()).isEqualTo(AiPolicy.HINT_ONLY);
        assertThat(json.valueToTree(old.criteria()).get(0).path("name").asText()).isEqualTo("근거 제시");assertThat(old.antiCheat().path("windowLeave").asBoolean()).isTrue();
        assertThat(work.workspace(first,p.participantToken()).questions().get(0).answer().content()).isEqualTo("원래 답안");
        assertThat(fresh.questions().get(0).title()).isEqualTo("새 문항");assertThat(fresh.questions().get(0).minChars()).isEqualTo(4);
        assertThat(fresh.aiPolicy()).isEqualTo(AiPolicy.FULL);assertThat(json.valueToTree(fresh.criteria()).get(0).path("name").asText()).isEqualTo("새 기준");
        assertThat(fresh.antiCheat().path("windowLeave").asBoolean()).isFalse();
        assertThat(grading.rubric(first).criteria().get(0).name()).isEqualTo("근거 제시");
        assertThat(grading.rubric(second).criteria().get(0).name()).isEqualTo("새 기준");
        templates.end(t.id(),first);templates.update(t.id(),input("또 수정",0,10));
        assertThat(work.configuration(first).questions().get(0).id()).isEqualTo(q);
        String third=add(t.id(),"3반");assertThat(work.configuration(third).questions().get(0).maxChars()).isEqualTo(10);
    }
    @Test void listCountsAndSubmissionAreIsolatedPerClass() throws Exception {
        var t=createTemplate();String one=add(t.id(),"1반"),two=add(t.id(),"2반");var a=startClass(t.id(),one);var b=startClass(t.id(),two);
        var p=evaluations.join(a.pin(),"같은 닉네임",null);var other=evaluations.join(b.pin(),"같은 닉네임",null);
        var review=work.review(one,p.participantToken());work.submit(one,p.participantToken(),review.reviewToken());
        assertThatThrownBy(()->work.workspace(two,p.participantToken())).isInstanceOf(ApiError.Failure.class);
        var list=ok(request(get("/api/teacher/assessments"),teacher));JsonNode row=null;
        for(var n:list.path("items"))if(n.path("id").asText().equals(t.id()))row=n;
        assertThat(row).isNotNull();assertThat(row.path("questionCount").asInt()).isEqualTo(1);assertThat(row.path("classCount").asInt()).isEqualTo(2);
        var classes=ok(request(get("/api/teacher/assessments/"+t.id()+"/classes"),teacher));
        assertThat(classes.get(0).path("participantCount").asInt()).isEqualTo(1);assertThat(classes.get(0).path("submittedCount").asInt()).isEqualTo(1);
        assertThat(classes.get(1).path("submittedCount").asInt()).isZero();
        mvc.perform(get("/api/student/evaluations/"+two+"/workspace").header("X-Participant-Token",other.participantToken())).andExpect(status().isOk());
    }
    @Test void namesAreNormalizedUniqueWithinParentAndCrossParentIdsAreRejected() throws Exception {
        var t=createTemplate();String c=add(t.id()," Class A ");
        mvc.perform(body(request(post("/api/teacher/assessments/"+t.id()+"/classes"),teacher),Map.of("className","ＣＬＡＳＳ A"))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("CLASS_NAME_TAKEN"));
        var other=createTemplate();add(other.id(),"Class A");
        mvc.perform(body(request(post("/api/teacher/assessments/"+other.id()+"/classes/"+c+"/start"),teacher),Map.of("expiresInMinutes",60))).andExpect(status().isNotFound());
        mvc.perform(body(request(post("/api/teacher/assessments/"+t.id()+"/classes"),teacher),Map.of("className","가".repeat(31)))).andExpect(status().isBadRequest());
        mvc.perform(body(request(post("/api/teacher/assessments/"+t.id()+"/classes"),teacher),Map.of("className","   "))).andExpect(status().isBadRequest());
    }
    @Test void otherTeachersCannotReadModifyStartOrDeleteTemplates() throws Exception {
        var t=createTemplate();String c=add(t.id(),"1반");var other=newTeacher();
        mvc.perform(request(get("/api/teacher/assessments/"+t.id()),other)).andExpect(status().isNotFound());
        mvc.perform(body(request(put("/api/teacher/assessments/"+t.id()),other),input("침범",0,10))).andExpect(status().isNotFound());
        mvc.perform(body(request(post("/api/teacher/assessments/"+t.id()+"/classes/"+c+"/start"),other),Map.of("expiresInMinutes",60))).andExpect(status().isNotFound());
        mvc.perform(request(delete("/api/teacher/assessments/"+t.id()),other)).andExpect(status().isNotFound());
        mvc.perform(request(get("/api/teacher/assessments"),null)).andExpect(status().isUnauthorized());
        var mine=ok(request(get("/api/teacher/assessments"),other));assertThat(mine.path("totalElements").asLong()).isZero();
    }
    @Test void existingMutationEndpointsCannotBypassTemplateSnapshots() throws Exception {
        var t=createTemplate();String c=add(t.id(),"1반");String root="/api/teacher/evaluations/"+c;
        mvc.perform(body(request(put(root+"/configuration"),teacher),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"q","c"))))).andExpect(status().isConflict());
        mvc.perform(body(request(put(root+"/content"),teacher),Map.of("evaluationContent","변경"))).andExpect(status().isConflict());
        mvc.perform(body(request(patch(root),teacher),Map.of("title","독립 변경"))).andExpect(status().isConflict());
        mvc.perform(request(post(root+"/start"),teacher)).andExpect(status().isConflict());
        mvc.perform(request(delete(root),teacher)).andExpect(status().isConflict());
        mvc.perform(body(request(put(root+"/rubric"),teacher),Map.of("criteria",input("x",0,10).criteria()))).andExpect(status().isConflict());
        mvc.perform(request(delete(root+"/rubric"),teacher)).andExpect(status().isConflict());
    }
    @Test void deleteBlocksActiveClassesThenCascadesSubmittedAndGradedRecords() throws Exception {
        var t=createTemplate();String c=add(t.id(),"1반"),waiting=add(t.id(),"2반");var started=startClass(t.id(),c);
        var p=evaluations.join(started.pin(),"학생",null);var q=work.configuration(c).questions().get(0).id();
        work.save(c,p.participantToken(),q,new SaveAnswer("작성한 답안",0,AnswerSource.DIRECT,null));var r=work.review(c,p.participantToken());work.submit(c,p.participantToken(),r.reviewToken());
        grading.saveScore(c,p.participantId(),1,new ScoreInput(3,"근거 있음"));grading.confirm(c,p.participantId(),false);
        mvc.perform(request(delete("/api/teacher/assessments/"+t.id()),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("ASSESSMENT_IN_PROGRESS"));
        as(teacher);assertThat(templates.classes(t.id())).hasSize(2);templates.end(t.id(),c);
        mvc.perform(request(delete("/api/teacher/assessments/"+t.id()+"/classes/"+c),teacher)).andExpect(status().isConflict());
        mvc.perform(request(delete("/api/teacher/assessments/"+t.id()+"/classes/"+waiting),teacher)).andExpect(status().isNoContent());
        mvc.perform(request(delete("/api/teacher/assessments/"+t.id()),teacher)).andExpect(status().isNoContent());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE assessment_id=?",Integer.class,t.id())).isZero();
        for(String table:List.of("submissions","participants","grading_scores","grading_results","grading_score_changes","notifications","questions","evaluation_rubrics"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE evaluation_id=?",Integer.class,c)).isZero();
    }
    @Test void invalidTemplateUpdateRollsBackTemplateAndAllWaitingSnapshots() throws Exception {
        var t=createTemplate();String c=add(t.id(),"1반");as(teacher);String q=work.configuration(c).questions().get(0).id();
        var bad=input("바꾸면 안됨",20,10);
        mvc.perform(body(request(put("/api/teacher/assessments/"+t.id()),teacher),bad)).andExpect(status().isBadRequest());
        as(teacher);assertThat(templates.detail(t.id()).revision()).isEqualTo(1);assertThat(work.configuration(c).questions().get(0).id()).isEqualTo(q);
        var object=json.valueToTree(input("안됨",0,10));((com.fasterxml.jackson.databind.node.ObjectNode)object).putArray("antiCheat").add("UNSUPPORTED");
        mvc.perform(body(request(put("/api/teacher/assessments/"+t.id()),teacher),object)).andExpect(status().isBadRequest());
        ((com.fasterxml.jackson.databind.node.ObjectNode)object).putObject("antiCheat").put("large","가".repeat(6000));
        mvc.perform(body(request(put("/api/teacher/assessments/"+t.id()),teacher),object)).andExpect(status().isBadRequest());
    }
    @Test void concurrentDuplicateClassCreationHasOneWinner() throws Exception {
        var t=createTemplate();var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        Callable<String> task=()->{gate.await();as(teacher);try{return templates.addClass(t.id(),"동시 반").classId();}catch(ApiError.Failure e){return e.code;}finally{clear();}};
        try {
            var a=executor.submit(task);var b=executor.submit(task);gate.countDown();
            var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertThat(results).contains("CLASS_NAME_TAKEN");as(teacher);assertThat(templates.classes(t.id())).hasSize(1);
        }finally{executor.shutdownNow();}
    }
    @Test void racingTemplateEditAndStartProduceACompleteSnapshot() throws Exception {
        var t=createTemplate();String c=add(t.id(),"1반");as(teacher);templates.openLobby(t.id(),c,60);var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=executor.submit(()->{as(teacher);try{gate.await();return templates.start(t.id(),c,60);}finally{clear();}});
            var b=executor.submit(()->{as(teacher);try{gate.await();return templates.update(t.id(),input("새 틀",3,30));}finally{clear();}});
            gate.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);as(teacher);
            var view=templates.classes(t.id()).get(0);var config=work.configuration(c);
            assertThat(view.state()).isEqualTo("STARTED");assertThat(config.questions()).hasSize(1);
            assertThat(config.questions().get(0).minChars()).isEqualTo(view.templateRevision()==1?null:3);
        }finally{executor.shutdownNow();}
    }
}
