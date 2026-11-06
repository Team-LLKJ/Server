package kr.lit.api;

import kr.lit.auth.*;
import kr.lit.auth.AuthModels.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthApiTest extends AuthTestSupport {
    Map<String,Object> signup(String token,String suffix) {return Map.of("username","t_"+suffix,"email",suffix+"@example.test","password",PASSWORD,"name","교사","emailVerificationToken",token);}
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
    @Test void emailVerificationReplacesInvitationsAndSignupFailureDoesNotConsumeToken() throws Exception {
        mvc.perform(request(body(post("/api/auth/teacher-invitations"),Map.of()),teacher)).andExpect(status().isGone());
        String suffix=unique(),token=createVerifiedEmail(suffix+"@example.test");
        var duplicate=new LinkedHashMap<>(signup(token,suffix));duplicate.put("username",teacher.user().username());
        mvc.perform(request(body(post("/api/auth/signup/teacher"),duplicate),null)).andExpect(status().isConflict());
        var user=ok(request(body(post("/api/auth/signup/teacher"),signup(token,suffix)),null));
        assertThat(user.path("role").asText()).isEqualTo("TEACHER");assertThat(user.path("school").asText()).isEmpty();
        mvc.perform(request(body(post("/api/auth/signup/teacher"),signup(token,suffix)),null)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("EMAIL_VERIFICATION_REQUIRED"));
    }
    @Test void loginAndRefreshIssueTwoHourAccessTokens() throws Exception {
        var before=Instant.now();
        var login=ok(request(body(post("/api/auth/login"),new Login(teacher.user().username(),PASSWORD)),null));
        assertThat(Instant.parse(login.path("accessExpiresAt").asText()))
                .isBetween(before.plusSeconds(7200),Instant.now().plusSeconds(7200));
        var refreshedBefore=Instant.now();
        var refreshed=ok(request(body(post("/api/auth/refresh"),new Refresh(login.path("refreshToken").asText())),null));
        assertThat(Instant.parse(refreshed.path("accessExpiresAt").asText()))
                .isBetween(refreshedBefore.plusSeconds(7200),Instant.now().plusSeconds(7200));
        assertThat(refreshed.path("refreshExpiresAt").asText()).isEqualTo(login.path("refreshExpiresAt").asText());
    }
    @Test void accessTokenCannotOutliveRefreshFamily() throws Exception {
        var familyExpiry=OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).withNano(0);
        db.update("UPDATE auth_sessions SET refresh_expires_at=? WHERE refresh_hash=?",familyExpiry,AuthService.hash(teacher.refreshToken()));
        var refreshed=ok(request(body(post("/api/auth/refresh"),new Refresh(teacher.refreshToken())),null));
        assertThat(Instant.parse(refreshed.path("accessExpiresAt").asText())).isEqualTo(familyExpiry.toInstant());
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
    @Test void concurrentVerificationConsumptionAllowsOneSignup() throws Exception {
        String suffix=unique(),email=suffix+"@example.test",token=createVerifiedEmail(email);
        var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        Callable<Boolean> register=()->{gate.await();try{auth.signupTeacher(new TeacherSignup("t_"+unique(),PASSWORD,email,"교사",token));return true;}catch(ApiError.Failure ex){return false;}};
        try{var a=executor.submit(register);var b=executor.submit(register);gate.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);}finally{executor.shutdownNow();}
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
