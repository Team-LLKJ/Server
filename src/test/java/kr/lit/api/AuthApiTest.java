package kr.lit.api;

import kr.lit.auth.*;
import kr.lit.auth.AuthModels.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthApiTest extends AuthTestSupport {
    Map<String,Object> signup(String code,String suffix,String school) {return Map.of("username","t_"+suffix,"email",suffix+"@example.test","password",PASSWORD,"name","교사","school",school,"inviteCode",code);}
    @Test void studentsNeedNoAccountsAndCannotCreateClasses() throws Exception {
        int usersBefore=db.queryForObject("SELECT COUNT(*) FROM app_users",Integer.class);
        mvc.perform(request(body(post("/api/auth/signup/student"),Map.of("role","TEACHER")),null)).andExpect(status().isGone()).andExpect(jsonPath("code").value("STUDENT_ACCOUNTS_DISABLED"));
        mvc.perform(request(body(post("/api/teacher/evaluations"),Map.of("title","수업","expiresInMinutes",60)),null)).andExpect(status().isUnauthorized());
        var e=ok(request(body(post("/api/teacher/evaluations"),Map.of("title","수업","expiresInMinutes",60)),teacher));
        var joined=ok(request(body(post("/api/participation/join"),Map.of("pin",e.get("pin").asText(),"nickname","비회원학생")),null));
        assertThat(joined.get("participantToken").asText()).hasSize(64);
        assertThat(db.queryForObject("SELECT user_id FROM participants WHERE id=?",String.class,joined.get("participantId").asText())).isNull();
        assertThat(db.queryForObject("SELECT token FROM participants WHERE id=?",String.class,joined.get("participantId").asText())).isEqualTo(AuthService.hash(joined.get("participantToken").asText()));
        mvc.perform(request(post("/api/teacher/evaluations/"+e.get("id").asText()+"/start"),null).header("X-Participant-Token",joined.get("participantToken").asText())).andExpect(status().isUnauthorized());
        mvc.perform(request(get("/api/evaluations/"+e.get("id").asText()+"/state"),teacher)).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("PARTICIPANT_TOKEN_REQUIRED"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users",Integer.class)).isEqualTo(usersBefore);
    }
    @Test void invitationsAreSchoolBoundSingleUseAndRollbackSafe() throws Exception {
        var i=ok(request(body(post("/api/auth/teacher-invitations"),new Invite(24)),teacher));String code=i.get("inviteCode").asText();
        mvc.perform(request(body(post("/api/auth/signup/teacher"),signup(code,unique(),"다른 학교")),null)).andExpect(status().isBadRequest());
        var duplicate=new LinkedHashMap<>(signup(code,unique(),"테스트학교"));duplicate.put("username",teacher.user().username());
        mvc.perform(request(body(post("/api/auth/signup/teacher"),duplicate),null)).andExpect(status().isConflict());
        assertThat(ok(request(body(post("/api/auth/signup/teacher"),signup(code,unique(),"테스트학교")),null)).get("role").asText()).isEqualTo("TEACHER");
        mvc.perform(request(body(post("/api/auth/signup/teacher"),signup(code,unique(),"테스트학교")),null)).andExpect(status().isBadRequest());
    }
    @Test void refreshRotationAndReuseRevokeSessionFamily() throws Exception {
        var refreshed=ok(request(body(post("/api/auth/refresh"),new Refresh(teacher.refreshToken())),null));String access=refreshed.get("accessToken").asText();
        mvc.perform(request(get("/api/auth/me"),teacher)).andExpect(status().isUnauthorized());
        mvc.perform(request(get("/api/auth/me"),null).header("Authorization","Bearer "+access)).andExpect(status().isOk());
        mvc.perform(request(body(post("/api/auth/refresh"),new Refresh(teacher.refreshToken())),null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("REFRESH_TOKEN_REUSED"));
        mvc.perform(request(get("/api/auth/me"),null).header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
    }
    @Test void logoutExpiryAndOtherTeacherOwnership() throws Exception {
        var e=ok(request(body(post("/api/teacher/evaluations"),Map.of("title","수업","expiresInMinutes",60)),teacher));var other=newTeacher();
        for(String suffix:List.of("","/questions","/participants","/qr"))mvc.perform(request(get("/api/teacher/evaluations/"+e.get("id").asText()+suffix),other)).andExpect(status().isNotFound());
        ok(request(post("/api/auth/logout"),teacher));mvc.perform(request(get("/api/auth/me"),teacher)).andExpect(status().isUnauthorized());
        mvc.perform(request(body(post("/api/auth/refresh"),new Refresh(teacher.refreshToken())),null)).andExpect(status().isUnauthorized());
        db.update("UPDATE auth_sessions SET access_expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE user_id=?",other.user().id());
        mvc.perform(request(get("/api/auth/me"),other)).andExpect(status().isUnauthorized());
    }
    @Test void legacyStudentAccountsCannotLoginRefreshOrUseAccessToken() throws Exception {
        var legacy=newTeacher();db.update("UPDATE app_users SET user_role='STUDENT' WHERE id=?",legacy.user().id());
        mvc.perform(request(body(post("/api/auth/login"),new Login(legacy.user().username(),PASSWORD)),null)).andExpect(status().isUnauthorized());
        mvc.perform(request(get("/api/auth/me"),legacy)).andExpect(status().isUnauthorized());
        mvc.perform(request(body(post("/api/auth/refresh"),new Refresh(legacy.refreshToken())),null)).andExpect(status().isUnauthorized());
    }
    @Test void concurrentInviteConsumptionAndExpiry() throws Exception {
        String code=UUID.randomUUID().toString()+UUID.randomUUID();auth.bootstrap(code,"테스트학교");var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        Callable<Boolean> register=()->{gate.await();String s=unique();try{auth.signupTeacher(new TeacherSignup("t_"+s,PASSWORD,s+"@example.test","교사","테스트학교",code));return true;}catch(ApiError.Failure ex){return false;}};
        try{var a=executor.submit(register);var b=executor.submit(register);gate.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);}finally{executor.shutdownNow();}
        auth.bootstrap(code,"테스트학교");mvc.perform(request(body(post("/api/auth/signup/teacher"),signup(code,unique(),"테스트학교")),null)).andExpect(status().isBadRequest());
        String expired=UUID.randomUUID().toString()+UUID.randomUUID();auth.bootstrap(expired,"테스트학교");db.update("UPDATE teacher_invites SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE code_hash=?",AuthService.hash(expired));
        mvc.perform(request(body(post("/api/auth/signup/teacher"),signup(expired,unique(),"테스트학교")),null)).andExpect(status().isBadRequest());
    }
    @Test void failedLoginCounterTemporarilyLocksTeacherAccount() throws Exception {
        for(int n=0;n<5;n++)mvc.perform(request(body(post("/api/auth/login"),new Login(teacher.user().username(),"wrong-password")),null)).andExpect(status().isUnauthorized());
        mvc.perform(request(body(post("/api/auth/login"),new Login(teacher.user().username(),PASSWORD)),null)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT failed_logins FROM app_users WHERE id=?",Integer.class,teacher.user().id())).isEqualTo(5);
        db.update("UPDATE app_users SET locked_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",teacher.user().id());
        ok(request(body(post("/api/auth/login"),new Login(teacher.user().username(),PASSWORD)),null));
    }
    @Test void passwordRecoveryEndpointsAreNotAvailable() throws Exception {
        for(String endpoint:List.of("request","confirm")) {
            mvc.perform(request(body(post("/api/auth/password-reset/"+endpoint),Map.of()),null)).andExpect(status().isNotFound());
            mvc.perform(request(body(post("/api/auth/password-reset/"+endpoint),Map.of()),teacher)).andExpect(status().isNotFound());
        }
    }
}
