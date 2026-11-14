package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import kr.lit.auth.AuthModels.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static kr.lit.api.TemplateModels.*;
import static kr.lit.api.WorkModels.*;
import static kr.lit.api.AssessmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CollaborationApiTest extends AuthTestSupport {
    @Autowired TemplateService templates;
    @Autowired AssessmentService grading;
    record Fixture(String id,String classId,Tokens editor) {}
    Input input(String title) {
        return new Input(title,"<h2>공통 안내</h2><p>"+"가".repeat(250)+"</p>",AiPolicy.FULL,
                List.of(new CriterionInput("직접 채점",100,null,null,Indicator.MANUAL)),json.valueToTree(List.of("TAB_LEAVE","PRINT_BLOCK")),
                List.of(new QuestionInput(1,"질문","근거를 제시하세요.")),45);
    }
    Fixture fixture() throws Exception {
        var editor=newTeacher();as(teacher);var t=templates.create(input("협업 평가"));var c=templates.addClass(t.id(),"1반");
        ok(body(request(post(base(t.id())+"/collaborators"),teacher),Map.of("email",editor.user().email())));
        return new Fixture(t.id(),c.classId(),editor);
    }
    String base(String id) {return "/api/teacher/assessments/"+id;}
    String eval(String id) {return "/api/teacher/evaluations/"+id;}
    EvaluationService.Joined participant(Fixture f) {
        as(f.editor());templates.openLobby(f.id(),f.classId(),60);var c=templates.start(f.id(),f.classId(),45);var p=evaluations.join(c.pin(),"참가 학생",null);
        var q=work.configuration(f.classId()).questions().get(0).id();
        work.save(f.classId(),p.participantToken(),q,new SaveAnswer("내가 작성한 근거입니다.",0,AnswerSource.DIRECT,null));
        return p;
    }
    void submit(Fixture f,EvaluationService.Joined p) {as(f.editor());var review=work.review(f.classId(),p.participantToken());work.submit(f.classId(),p.participantToken(),review.reviewToken());}
    long notices(String uid,String type,String assessmentId) {return db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND notification_type=? AND assessment_id=?",Long.class,uid,type,assessmentId);}
    @Test void shareReturnsTeacherAndListsMineAndSharedWithOwnerRoleAndClassSummary() throws Exception {
        var f=fixture();
        var owner=ok(request(get(base(f.id())),teacher));assertThat(owner.path("role").asText()).isEqualTo("OWNER");assertThat(owner.path("owner").path("teacherId").asText()).isEqualTo(teacher.user().id());
        var detail=ok(request(get(base(f.id())),f.editor()));assertThat(detail.path("role").asText()).isEqualTo("EDITOR");assertThat(detail.path("classes").get(0).path("classId").asText()).isEqualTo(f.classId());
        var mine=ok(request(get("/api/teacher/assessments"),teacher));assertThat(mine.path("scope").asText()).isEqualTo("mine");
        var editorMine=ok(request(get("/api/teacher/assessments"),f.editor()));assertThat(editorMine.path("totalElements").asInt()).isZero();
        var shared=ok(request(get("/api/teacher/assessments?scope=shared"),f.editor()));assertThat(shared.path("totalElements").asInt()).isEqualTo(1);
        var row=shared.path("items").get(0);assertThat(row.path("role").asText()).isEqualTo("EDITOR");assertThat(row.path("owner").path("name").asText()).isEqualTo("교사");
        assertThat(row.path("guidelinesSummary").asText()).doesNotContain("<");assertThat(row.path("guidelinesSummary").asText().codePointCount(0,row.path("guidelinesSummary").asText().length())).isEqualTo(200);
        assertThat(row.path("aiPolicy").asText()).isEqualTo("FULL");
        var collaborators=ok(request(get(base(f.id())+"/collaborators"),teacher));assertThat(collaborators.get(0).path("teacherId").asText()).isEqualTo(f.editor().user().id());
        assertThat(collaborators.get(0).path("email").asText()).isEqualTo(f.editor().user().email());assertThat(collaborators.get(0).path("sharedAt").asText()).isNotBlank();
        mvc.perform(request(get("/api/teacher/assessments?scope=invalid"),teacher)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_SCOPE"));
    }
    @Test void shareValidatesMissingDisabledStudentSelfDuplicateAndNormalizedEmail() throws Exception {
        as(teacher);var t=templates.create(input("공유 검사"));String url=base(t.id())+"/collaborators";
        mvc.perform(body(request(post(url),teacher),Map.of("email","missing@example.test"))).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("TEACHER_NOT_FOUND"));
        mvc.perform(body(request(post(url),teacher),Map.of("email",teacher.user().email()))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("CANNOT_SHARE_WITH_SELF"));
        var disabled=newTeacher();db.update("UPDATE app_users SET user_status='DISABLED' WHERE id=?",disabled.user().id());
        mvc.perform(body(request(post(url),teacher),Map.of("email",disabled.user().email()))).andExpect(status().isNotFound());
        var student=newTeacher();db.update("UPDATE app_users SET user_role='STUDENT' WHERE id=?",student.user().id());
        mvc.perform(body(request(post(url),teacher),Map.of("email",student.user().email()))).andExpect(status().isNotFound());
        var target=newTeacher();mvc.perform(body(request(post(url),teacher),Map.of("email"," "+target.user().email().toUpperCase(Locale.ROOT)+" "))).andExpect(status().isCreated());
        mvc.perform(body(request(post(url),teacher),Map.of("email",target.user().email()))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("COLLABORATOR_ALREADY_EXISTS"));
        assertThat(notices(target.user().id(),"ASSESSMENT_SHARED",t.id())).isEqualTo(1);
    }
    @Test void editorCannotManageSharingOrDeleteTemplateButUnrelatedTeacherStillGets404() throws Exception {
        var f=fixture();var other=newTeacher();
        mvc.perform(body(request(post(base(f.id())+"/collaborators"),f.editor()),Map.of("email",other.user().email()))).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("ASSESSMENT_OWNER_REQUIRED"));
        mvc.perform(request(get(base(f.id())+"/collaborators"),f.editor())).andExpect(status().isOk());
        mvc.perform(request(delete(base(f.id())+"/collaborators/"+teacher.user().id()),f.editor())).andExpect(status().isForbidden());
        mvc.perform(request(delete(base(f.id())),f.editor())).andExpect(status().isForbidden());
        mvc.perform(request(get(base(f.id())),other)).andExpect(status().isNotFound());
        mvc.perform(request(get(eval(f.classId())+"/students"),other)).andExpect(status().isNotFound());
        assertThat(ok(request(get("/api/teacher/assessments?scope=shared"),other)).path("totalElements").asInt()).isZero();
    }
    @Test void editorCanUpdateTemplateOperateClassesAndUseExistingGradingAndReportApis() throws Exception {
        var f=fixture();ok(body(request(put(base(f.id())),f.editor()),input("편집자가 수정")));
        var extra=ok(body(request(post(base(f.id())+"/classes"),f.editor()),Map.of("className","추가 반")));
        assertThat(db.queryForObject("SELECT teacher_id FROM evaluations WHERE id=?",String.class,extra.path("classId").asText())).isEqualTo(teacher.user().id());
        mvc.perform(request(delete(base(f.id())+"/classes/"+extra.path("classId").asText()),f.editor())).andExpect(status().isNoContent());
        var p=participant(f);submit(f,p);String root=eval(f.classId()),pid=p.participantId();
        for(String suffix:List.of("","/participants","/students","/submissions","/configuration","/content","/rubric","/students/"+pid,
                "/students/"+pid+"/answers","/students/"+pid+"/conversations","/students/"+pid+"/activity","/students/"+pid+"/review","/students/"+pid+"/review/history",
                "/participants/"+pid+"/answers","/participants/"+pid+"/conversations","/participants/"+pid+"/analysis","/participants/"+pid+"/rubric-scores","/participants/"+pid+"/rubric-scores/history"))
            mvc.perform(request(get(root+suffix),f.editor())).andExpect(status().isOk());
        mvc.perform(body(request(post(root+"/extend"),f.editor()),Map.of("additionalMinutes",10))).andExpect(status().isOk());
        mvc.perform(body(request(put(root+"/participants/"+pid+"/rubric-scores/1"),f.editor()),Map.of("level",3,"feedback","검토 완료"))).andExpect(status().isOk());
        mvc.perform(body(request(post(root+"/participants/"+pid+"/grade/confirm"),f.editor()),Map.of("acceptAiForMissing",false))).andExpect(status().isOk()).andExpect(jsonPath("status").value("CONFIRMED"));
        assertThat(db.queryForObject("SELECT confirmed_by FROM grading_results WHERE participant_id=?",String.class,pid)).isEqualTo(f.editor().user().id());
        mvc.perform(request(post(root+"/students/"+pid+"/reports"),f.editor())).andExpect(status().isOk());
        mvc.perform(request(get(root+"/students/"+pid+"/report.pdf"),f.editor())).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        // 프런트가 쓰는 기존 end 경로도 공유 회차에 그대로 동작합니다.
        mvc.perform(request(post(root+"/end"),f.editor())).andExpect(status().isOk()).andExpect(jsonPath("state").value("ENDED"));
        mvc.perform(request(post(root+"/end"),f.editor())).andExpect(status().isOk());
    }
    @Test void notificationsReachOwnerAndEditorsOnceAndGradeReconfirmationHasNewVersion() throws Exception {
        var f=fixture();var p=participant(f);as(f.editor());
        work.recordClientEvent(f.classId(),p.participantToken(),new ClientEvent(ClientEventType.WINDOW_LEAVE,Instant.now(),null));
        work.recordClientEvent(f.classId(),p.participantToken(),new ClientEvent(ClientEventType.SHORTCUT_CAPTURE,Instant.now(),null));
        submit(f,p);as(f.editor());grading.saveScore(f.classId(),p.participantId(),1,new ScoreInput(3,"確認"));grading.confirm(f.classId(),p.participantId(),false);grading.confirm(f.classId(),p.participantId(),false);
        for(var actor:List.of(teacher,f.editor()))for(String type:List.of("WARNING","SUBMISSION","GRADING_CONFIRMED"))assertThat(notices(actor.user().id(),type,f.id())).isEqualTo(1);
        grading.saveScore(f.classId(),p.participantId(),1,new ScoreInput(4,"수정"));grading.confirm(f.classId(),p.participantId(),false);templates.end(f.id(),f.classId());
        for(var actor:List.of(teacher,f.editor())) {assertThat(notices(actor.user().id(),"GRADING_CONFIRMED",f.id())).isEqualTo(2);assertThat(notices(actor.user().id(),"EVALUATION_ENDED",f.id())).isEqualTo(1);}
    }
    @Test void operationalNotificationsRespectEachSettingButShareNoticeIsAlwaysStored() throws Exception {
        var editor=newTeacher();as(editor);var ns=new NotificationService(db);ns.saveSettings(false,false);as(teacher);var t=templates.create(input("알림 설정"));
        ok(body(request(post(base(t.id())+"/collaborators"),teacher),Map.of("email",editor.user().email())));
        assertThat(notices(editor.user().id(),"ASSESSMENT_SHARED",t.id())).isEqualTo(1);
        as(teacher);var c=templates.addClass(t.id(),"1반");templates.openLobby(t.id(),c.classId(),60);var s=templates.start(t.id(),c.classId(),45);var p=evaluations.join(s.pin(),"학생",null);
        var review=work.review(c.classId(),p.participantToken());work.submit(c.classId(),p.participantToken(),review.reviewToken());
        assertThat(notices(teacher.user().id(),"SUBMISSION",t.id())).isEqualTo(1);assertThat(notices(editor.user().id(),"SUBMISSION",t.id())).isZero();
    }
    @Test void revocationImmediatelyBlocksTemplateAndExistingClassApisAndHidesLateNotifications() throws Exception {
        var f=fixture();var p=participant(f);submit(f,p);String root=eval(f.classId());
        var notice=ok(request(get("/api/notifications"),f.editor())).path("items").get(0).path("id").asLong();
        mvc.perform(request(delete(base(f.id())+"/collaborators/"+f.editor().user().id()),teacher)).andExpect(status().isNoContent());
        mvc.perform(request(get(base(f.id())),f.editor())).andExpect(status().isNotFound());
        for(String suffix:List.of("","/students","/submissions","/rubric","/students/"+p.participantId()+"/report.pdf","/participants/"+p.participantId()+"/rubric-scores"))
            mvc.perform(request(get(root+suffix),f.editor())).andExpect(status().isNotFound());
        mvc.perform(body(request(post(root+"/extend"),f.editor()),Map.of("additionalMinutes",10))).andExpect(status().isNotFound());
        mvc.perform(request(post(root+"/end"),f.editor())).andExpect(status().isNotFound());
        assertThat(ok(request(get("/api/teacher/assessments?scope=shared"),f.editor())).path("totalElements").asInt()).isZero();
        mvc.perform(request(patch("/api/notifications/"+notice+"/read"),f.editor())).andExpect(status().isNotFound());
        // 해제 중 이벤트가 늦게 저장된 상황도 알림 권한 필터가 차단합니다.
        db.update("INSERT INTO notifications(user_id,assessment_id,notification_type,title,body,evaluation_id,created_at) VALUES (?,?,'WARNING','늦은 알림','본문',?,CURRENT_TIMESTAMP)",f.editor().user().id(),f.id(),f.classId());
        mvc.perform(request(get("/api/notifications/unread-count"),f.editor())).andExpect(status().isOk()).andExpect(jsonPath("unreadCount").value(0));
        assertThat(ok(request(get("/api/notifications"),f.editor())).path("items").size()).isZero();
        mvc.perform(request(get(root+"/students"),teacher)).andExpect(status().isOk());
        // 학생 참가 자격은 공유 해제의 영향을 받지 않습니다(제출한 학생이라 상태 확인만 됩니다).
        mvc.perform(get("/api/student/evaluations/"+f.classId()+"/state").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk()).andExpect(jsonPath("screen").value("SUBMITTED"));
    }
    @Test void reSharingCreatesNewNoticeAndDeletionCleansMembersAndShareNotices() throws Exception {
        var f=fixture();mvc.perform(request(delete(base(f.id())+"/collaborators/"+f.editor().user().id()),teacher)).andExpect(status().isNoContent());
        mvc.perform(request(delete(base(f.id())+"/collaborators/"+f.editor().user().id()),teacher)).andExpect(status().isNotFound());
        mvc.perform(body(request(post(base(f.id())+"/collaborators"),teacher),Map.of("email",f.editor().user().email()))).andExpect(status().isCreated());
        assertThat(notices(f.editor().user().id(),"ASSESSMENT_SHARED",f.id())).isEqualTo(1);
        mvc.perform(request(delete(base(f.id())),teacher)).andExpect(status().isNoContent());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assessment_collaborators WHERE assessment_id=?",Integer.class,f.id())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notifications WHERE assessment_id=?",Integer.class,f.id())).isZero();
    }
    @Test void sharingOneTemplateNeverGrantsAccessToOtherTemplatesOrStandaloneEvaluations() throws Exception {
        var f=fixture();as(teacher);var other=templates.create(input("비공유 틀"));var c=templates.addClass(other.id(),"다른 반");var independent=evaluations.create("독립 평가",60);
        for(String id:List.of(c.classId(),independent.id()))mvc.perform(request(get(eval(id)+"/students"),f.editor())).andExpect(status().isNotFound());
        mvc.perform(request(get(base(other.id())),f.editor())).andExpect(status().isNotFound());
    }
    @Test void renameIsAllowedBeforeDuringAndAfterButOnlyChangesDisplayName() throws Exception {
        var f=fixture();String url=base(f.id())+"/classes/"+f.classId();
        mvc.perform(body(request(patch(url),f.editor()),Map.of("className"," 2학년 3반 "))).andExpect(status().isOk()).andExpect(jsonPath("className").value("2학년 3반"));
        var p=participant(f);as(f.editor());var before=evaluations.details(f.classId());String q=work.configuration(f.classId()).questions().get(0).id();
        var ends=evaluations.lobby(f.classId()).endsAt();
        mvc.perform(body(request(patch(url),f.editor()),Map.of("className","수정 반"))).andExpect(status().isOk()).andExpect(jsonPath("state").value("STARTED"));
        as(f.editor());assertThat(work.configuration(f.classId()).questions().get(0).id()).isEqualTo(q);assertThat(evaluations.details(f.classId()).pin()).isEqualTo(before.pin());assertThat(evaluations.lobby(f.classId()).endsAt()).isEqualTo(ends);
        mvc.perform(get("/api/student/evaluations/"+f.classId()+"/workspace").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk()).andExpect(jsonPath("className").value("수정 반"));
        as(teacher);templates.end(f.id(),f.classId());String frozen=db.queryForObject("SELECT snapshot FROM submissions WHERE participant_id=?",String.class,p.participantId());
        mvc.perform(body(request(patch(url),f.editor()),Map.of("className","종료 반"))).andExpect(status().isOk()).andExpect(jsonPath("state").value("ENDED"));
        assertThat(db.queryForObject("SELECT snapshot FROM submissions WHERE participant_id=?",String.class,p.participantId())).isEqualTo(frozen);
    }
    @Test void renameEnforcesParentMembershipNameRulesAndDuplicateNormalization() throws Exception {
        var f=fixture();as(teacher);templates.addClass(f.id(),"Class A");var other=templates.create(input("다른 틀"));
        String url=base(f.id())+"/classes/"+f.classId();
        mvc.perform(body(request(patch(url),f.editor()),Map.of("className","ＣＬＡＳＳ A"))).andExpect(status().isConflict());
        mvc.perform(body(request(patch(url),f.editor()),Map.of("className","가".repeat(31)))).andExpect(status().isBadRequest());
        mvc.perform(body(request(patch(base(other.id())+"/classes/"+f.classId()),teacher),Map.of("className","혼합"))).andExpect(status().isNotFound());
        mvc.perform(body(request(patch(url),newTeacher()),Map.of("className","침범"))).andExpect(status().isNotFound());
    }
    @Test void simultaneousSharingHasOneWinnerAndOneNotification() throws Exception {
        as(teacher);var t=templates.create(input("동시 공유"));var editor=newTeacher();var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        Callable<String> action=()->{as(teacher);try{gate.await();return templates.share(t.id(),editor.user().email()).teacherId();}catch(ApiError.Failure ex){return ex.code;}finally{clear();}};
        try {var a=executor.submit(action);var b=executor.submit(action);gate.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).contains("COLLABORATOR_ALREADY_EXISTS");assertThat(notices(editor.user().id(),"ASSESSMENT_SHARED",t.id())).isEqualTo(1);}
        finally{executor.shutdownNow();}
    }

    @Test void listAllIncludesOwnedAndSharedWithoutDuplicatesAndReturnsSharingMetadata() throws Exception {
        var f=fixture();as(f.editor());templates.create(input("편집자 소유"));
        var all=ok(request(get("/api/teacher/assessments?scope=all"),f.editor()));
        assertThat(all.path("totalElements").asInt()).isEqualTo(2);
        for(var row:all.path("items")) {
            assertThat(row.path("myRole").asText()).isEqualTo(row.path("role").asText());
            assertThat(row.path("collaboratorCount").asInt()).isEqualTo(row.path("id").asText().equals(f.id())?1:0);
        }
        var detail=ok(request(get(base(f.id())),f.editor()));
        assertThat(detail.path("myRole").asText()).isEqualTo("EDITOR");assertThat(detail.path("shared").asBoolean()).isTrue();
    }
    @Test void editorCanLeaveOnlyTheirOwnShareAndLosesAccessImmediately() throws Exception {
        var f=fixture();var second=newTeacher();
        ok(body(request(post(base(f.id())+"/collaborators"),teacher),Map.of("email",second.user().email(),"role","EDITOR")));
        var list=ok(request(get(base(f.id())+"/collaborators"),f.editor()));
        assertThat(list.get(0).path("role").asText()).isEqualTo("EDITOR");
        assertThat(list.get(0).path("userId").asText()).isNotBlank();
        mvc.perform(request(delete(base(f.id())+"/collaborators/"+second.user().id()),f.editor())).andExpect(status().isForbidden());
        mvc.perform(request(delete(base(f.id())+"/collaborators/"+f.editor().user().id()),f.editor())).andExpect(status().isNoContent());
        mvc.perform(request(get(base(f.id())),f.editor())).andExpect(status().isNotFound());
        assertThat(notices(f.editor().user().id(),"ASSESSMENT_SHARED",f.id())).isZero();
        assertThat(ok(request(get(base(f.id())),teacher)).path("collaboratorCount").asInt()).isEqualTo(1);
    }
    @Test void sharingRejectsInvalidRolesAndSeparatesMissingNonTeacherInactiveTargets() throws Exception {
        as(teacher);var t=templates.create(input("대상 검증"));String url=base(t.id())+"/collaborators";
        var target=newTeacher();
        mvc.perform(body(request(post(url),teacher),Map.of("email",target.user().email(),"role","OWNER"))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_COLLABORATOR_ROLE"));
        db.update("UPDATE app_users SET user_role='STUDENT' WHERE id=?",target.user().id());
        mvc.perform(body(request(post(url),teacher),Map.of("email",target.user().email(),"role","EDITOR"))).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("SHARE_TARGET_NOT_TEACHER"));
        db.update("UPDATE app_users SET user_role='TEACHER',user_status='DISABLED' WHERE id=?",target.user().id());
        mvc.perform(body(request(post(url),teacher),Map.of("email",target.user().email()))).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("SHARE_TARGET_INACTIVE"));
    }
    @Test void staleRevisionCannotOverwriteTemplateOrWaitingClassSnapshot() throws Exception {
        var f=fixture();var first=json.valueToTree(input("먼저 저장"));((com.fasterxml.jackson.databind.node.ObjectNode)first).put("revision",1);
        var saved=ok(body(request(put(base(f.id())),f.editor()),first));assertThat(saved.path("revision").asInt()).isEqualTo(2);
        var stale=json.valueToTree(input("덮어쓰기"));((com.fasterxml.jackson.databind.node.ObjectNode)stale).put("revision",1);
        mvc.perform(body(request(put(base(f.id())),teacher),stale)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("ASSESSMENT_REVISION_CONFLICT"));
        assertThat(ok(request(get(base(f.id())),teacher)).path("title").asText()).isEqualTo("먼저 저장");
        assertThat(db.queryForObject("SELECT template_revision FROM evaluations WHERE id=?",Integer.class,f.classId())).isEqualTo(2);
        ((com.fasterxml.jackson.databind.node.ObjectNode)stale).put("revision",2);
        mvc.perform(body(request(put(base(f.id())),teacher),stale)).andExpect(status().isOk()).andExpect(jsonPath("revision").value(3));
    }
}
