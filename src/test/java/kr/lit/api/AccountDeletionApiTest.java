package kr.lit.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import kr.lit.auth.*;
import static kr.lit.auth.AuthModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
class AccountDeletionApiTest extends AuthTestSupport {
    @Autowired TemplateService templates;
    @Autowired AssessmentService grading;
    @Autowired AccountDeletionService deletion;
    @MockitoSpyBean AccountDeletionRepository repository;
    record Fixture(String templateId,String evaluationId,String participantId,String token) {}
    Fixture createFixture() {
        as(teacher);
        var input=new TemplateModels.Input("탈퇴 삭제 대상","안내",WorkModels.AiPolicy.FULL,
            List.of(new AssessmentModels.CriterionInput("근거",100,"설명",null,AssessmentModels.Indicator.MANUAL)),
            json.valueToTree(Map.of()),List.of(new WorkModels.QuestionInput(1,"질문","설명",null,null)),60);
        var template=templates.create(input);String classId=templates.addClass(template.id(),"1반").classId();
        var started=templates.start(template.id(),classId,60);
        var participant=evaluations.join(started.pin(),"학생",null);String question=work.configuration(classId).questions().get(0).id();
        work.save(classId,participant.participantToken(),question,new WorkModels.SaveAnswer("학생 답안",0,WorkModels.AnswerSource.DIRECT,null));
        var review=work.review(classId,participant.participantToken());work.submit(classId,participant.participantToken(),review.reviewToken());
        grading.saveScore(classId,participant.participantId(),1,new AssessmentModels.ScoreInput(3,"근거"));grading.confirm(classId,participant.participantId(),false);
        templates.end(template.id(),classId);
        return new Fixture(template.id(),classId,participant.participantId(),participant.participantToken());
    }
    @Test void deleteOwnedDataAndSessionsButKeepOtherOwnersData() throws Exception {
        var fixture=createFixture();var other=newTeacher();
        as(other);var otherEvaluation=evaluations.create("다른 사람 평가",60);as(teacher);
        var secondSession=auth.login(new Login(teacher.user().username(),PASSWORD));
        db.update("INSERT INTO user_settings(user_id,updated_at) VALUES (?,CURRENT_TIMESTAMP)",teacher.user().id());
        db.update("INSERT INTO user_policy_consents VALUES (?,'TERMS_OF_SERVICE','v1',CURRENT_TIMESTAMP)",teacher.user().id());
        // 다른 소유자의 채점 이력에 남는 익명 작성자 참조가 FK 오류를 내지 않는지 확인합니다.
        db.update("INSERT INTO assessment_collaborators VALUES (?,?,?,CURRENT_TIMESTAMP)",fixture.templateId(),other.user().id(),teacher.user().id());
        mvc.perform(body(request(delete("/api/users/me"),teacher),Map.of("currentPassword",PASSWORD,"deleteOwnedData",true))).andExpect(status().isOk());
        for(String table:List.of("submissions","participants","grading_scores","grading_results","grading_score_changes","notifications","questions","evaluation_rubrics","teacher_reviews","grading_analyses","usage_reports","ai_disclosures","learning_events","ai_turns","teacher_review_changes","evaluation_settings"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE evaluation_id=?",Integer.class,fixture.evaluationId())).as(table).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM answers WHERE participant_id=?",Integer.class,fixture.participantId())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assessment_templates WHERE id=?",Integer.class,fixture.templateId())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE id=?",Integer.class,otherEvaluation.id())).isEqualTo(1);
        for(String table:List.of("auth_sessions","user_settings","user_policy_consents","notifications"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,teacher.user().id())).isZero();
        var account=db.queryForMap("SELECT * FROM app_users WHERE id=?",teacher.user().id());
        assertThat(account.get("USER_STATUS")).isEqualTo("DELETED");assertThat(account.get("EMAIL")).isNotEqualTo(teacher.user().email());assertThat(account.get("DISPLAY_NAME")).isEqualTo("탈퇴한 사용자");
        for(var user:List.of(teacher,secondSession)) {
            mvc.perform(request(get("/api/auth/me"),user)).andExpect(status().isUnauthorized());
            mvc.perform(body(request(post("/api/auth/refresh"),null),new Refresh(user.refreshToken()))).andExpect(status().isUnauthorized());
        }
        mvc.perform(body(request(post("/api/auth/login"),null),new Login(teacher.user().username(),PASSWORD))).andExpect(status().isUnauthorized());
        String proof=createVerifiedEmail(teacher.user().email());
        assertThat(auth.signupTeacher(new TeacherSignup(teacher.user().username(),PASSWORD,teacher.user().email(),"재가입",proof)).id()).isNotEqualTo(teacher.user().id());
    }
    @Test void rejectWrongPasswordMissingConsentAndRunningEvaluationWithoutDeletingAnything() throws Exception {
        as(teacher);var evaluation=evaluations.create("진행 중 평가",60);evaluations.join(evaluation.pin(),"참가자",null);evaluations.start(evaluation.id());
        mvc.perform(body(request(delete("/api/users/me"),teacher),Map.of("currentPassword","wrong","deleteOwnedData",true))).andExpect(status().isBadRequest());
        mvc.perform(body(request(delete("/api/users/me"),teacher),Map.of("currentPassword",PASSWORD))).andExpect(status().isBadRequest());
        mvc.perform(body(request(delete("/api/users/me"),null),Map.of("currentPassword",PASSWORD,"deleteOwnedData",true))).andExpect(status().isUnauthorized());
        mvc.perform(body(request(delete("/api/users/me"),teacher),Map.of("currentPassword",PASSWORD,"deleteOwnedData",true))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("EVALUATION_IN_PROGRESS"));
        assertThat(auth.me(teacher.user().id()).status()).isEqualTo("ACTIVE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE id=?",Integer.class,evaluation.id())).isEqualTo(1);
    }
    @Test void failureLateInDeletionRollsBackOwnedDataAndSessions() {
        var fixture=createFixture();var principal=auth.authenticate(teacher.accessToken());
        doThrow(new IllegalStateException("test failure")).when(repository).anonymizeAccount(eq(teacher.user().id()),anyString());
        assertThatThrownBy(()->deletion.deleteAccount(principal,PASSWORD)).isInstanceOf(IllegalStateException.class);
        assertThat(auth.authenticate(teacher.accessToken()).userId()).isEqualTo(teacher.user().id());
        assertThat(auth.me(teacher.user().id()).email()).isEqualTo(teacher.user().email());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM answers WHERE participant_id=?",Integer.class,fixture.participantId())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assessment_templates WHERE id=?",Integer.class,fixture.templateId())).isEqualTo(1);
    }
    @Test void deletionRemovesOnlyOwnCollaboratorMembershipOnOtherTemplates() throws Exception {
        var fixture=createFixture();var other=newTeacher();
        db.update("UPDATE assessment_templates SET teacher_id=? WHERE id=?",other.user().id(),fixture.templateId());
        db.update("INSERT INTO assessment_collaborators VALUES (?,?,?,CURRENT_TIMESTAMP)",fixture.templateId(),teacher.user().id(),other.user().id());
        mvc.perform(body(request(delete("/api/users/me"),teacher),Map.of("currentPassword",PASSWORD,"deleteOwnedData",true))).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assessment_templates WHERE id=?",Integer.class,fixture.templateId())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM answers WHERE participant_id=?",Integer.class,fixture.participantId())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assessment_collaborators WHERE teacher_id=?",Integer.class,teacher.user().id())).isZero();
    }
}
