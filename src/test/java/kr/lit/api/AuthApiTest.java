package kr.lit.api;

import kr.lit.auth.*;
import kr.lit.auth.AuthModels.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthApiTest extends AuthTestSupport {
    Map<String,Object> studentBody(String username,String email) {
        return new LinkedHashMap<>(Map.of("username",username,"email",email,"password",PASSWORD,"name","학생","school","테스트학교","grade",1,"classNumber",2,"studentNumber",3));
    }
    Map<String,Object> teacherBody(String code,String suffix,String school) {
        return Map.of("username","t_"+suffix,"email",suffix+"@example.test","password",PASSWORD,"name","교사","school",school,"inviteCode",code);
    }
    @Test void studentSignupCannotChooseTeacherRoleAndSecretsAreHashed() throws Exception {
        String suffix=unique();var signup=studentBody("S_"+suffix,suffix+"@EXAMPLE.TEST");signup.put("role","TEACHER");
        var registered=ok(request(body(post("/api/auth/signup/student"),signup),null));
        assertThat(registered.get("role").asText()).isEqualTo("STUDENT");
        assertThat(registered.get("username").asText()).isEqualTo("s_"+suffix);
        assertThat(registered.toString()).doesNotContain("passwordHash",PASSWORD);
        var tokens=ok(request(body(post("/api/auth/login"),new Login("S_"+suffix,PASSWORD)),null));
        assertThat(tokens.get("accessToken").asText()).hasSize(43);
        String uid=registered.get("id").asText();
        assertThat(db.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,uid)).isNotEqualTo(PASSWORD);
        assertThat(db.queryForObject("SELECT access_hash FROM auth_sessions WHERE user_id=?",String.class,uid)).isEqualTo(AuthService.hash(tokens.get("accessToken").asText()));
        mvc.perform(request(body(post("/api/auth/signup/student"),signup),null)).andExpect(status().isConflict());
        mvc.perform(request(get("/api/auth/me"),null).header("Authorization","Bearer "+tokens.get("accessToken").asText())).andExpect(jsonPath("role").value("STUDENT"));
    }
    @Test void teacherInvitationIsSchoolBoundSingleUseAndRollbackSafe() throws Exception {
        var invitation=ok(request(body(post("/api/auth/teacher-invitations"),new Invite(24)),teacher));
        String code=invitation.get("inviteCode").asText();
        assertThat(invitation.get("school").asText()).isEqualTo("테스트학교");
        mvc.perform(request(body(post("/api/auth/signup/teacher"),teacherBody(code,unique(),"다른 학교")),null)).andExpect(status().isBadRequest());
        String suffix=unique();var duplicate=new LinkedHashMap<String,Object>(teacherBody(code,suffix,"테스트학교"));duplicate.put("username",student.user().username());
        mvc.perform(request(body(post("/api/auth/signup/teacher"),duplicate),null)).andExpect(status().isConflict());
        var registered=ok(request(body(post("/api/auth/signup/teacher"),teacherBody(code,suffix,"테스트학교")),null));
        assertThat(registered.get("role").asText()).isEqualTo("TEACHER");
        mvc.perform(request(body(post("/api/auth/signup/teacher"),teacherBody(code,unique(),"테스트학교")),null)).andExpect(status().isBadRequest());
        mvc.perform(request(body(post("/api/auth/teacher-invitations"),new Invite(24)),student)).andExpect(status().isForbidden());
    }
    @Test void expiredInvitationAndBootstrapRestartDoNotReenableUsedCode() throws Exception {
        String code=UUID.randomUUID().toString()+UUID.randomUUID();auth.bootstrap(code,"테스트학교");
        db.update("UPDATE teacher_invites SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE code_hash=?",AuthService.hash(code));
        auth.bootstrap(code,"테스트학교");
        mvc.perform(request(body(post("/api/auth/signup/teacher"),teacherBody(code,unique(),"테스트학교")),null)).andExpect(status().isBadRequest());
    }
    @Test void concurrentInviteConsumptionHasOneWinner() throws Exception {
        String code=UUID.randomUUID().toString()+UUID.randomUUID();auth.bootstrap(code,"테스트학교");
        var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        Callable<Boolean> register=()->{gate.await();String s=unique();try{auth.signupTeacher(new TeacherSignup("t_"+s,PASSWORD,s+"@example.test","교사","테스트학교",code));return true;}catch(ApiError.Failure e){return false;}};
        try{var a=executor.submit(register);var b=executor.submit(register);gate.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);}finally{executor.shutdownNow();}
    }
    @Test void refreshRotationAndReplayRevokeTheWholeSessionFamily() throws Exception {
        var refreshed=ok(request(body(post("/api/auth/refresh"),new Refresh(student.refreshToken())),null));
        String newAccess=refreshed.get("accessToken").asText();
        mvc.perform(request(get("/api/auth/me"),student)).andExpect(status().isUnauthorized());
        mvc.perform(request(get("/api/auth/me"),null).header("Authorization","Bearer "+newAccess)).andExpect(status().isOk());
        mvc.perform(request(body(post("/api/auth/refresh"),new Refresh(student.refreshToken())),null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("REFRESH_TOKEN_REUSED"));
        mvc.perform(request(get("/api/auth/me"),null).header("Authorization","Bearer "+newAccess)).andExpect(status().isUnauthorized());
        mvc.perform(request(body(post("/api/auth/refresh"),new Refresh(refreshed.get("refreshToken").asText())),null)).andExpect(status().isUnauthorized());
    }
    @Test void logoutAndExpiryRejectTokensAndOldHeadersDoNotAuthenticate() throws Exception {
        ok(request(post("/api/auth/logout"),student));
        mvc.perform(request(get("/api/auth/me"),student)).andExpect(status().isUnauthorized());
        mvc.perform(request(body(post("/api/auth/refresh"),new Refresh(student.refreshToken())),null)).andExpect(status().isUnauthorized());
        db.update("UPDATE auth_sessions SET access_expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE user_id=?",otherStudent.user().id());
        mvc.perform(request(get("/api/auth/me"),otherStudent)).andExpect(status().isUnauthorized());
        mvc.perform(request(get("/api/auth/me"),null).header("X-Participant-Token","legacy-token")).andExpect(status().isUnauthorized());
        mvc.perform(request(get("/api/auth/me"),null).header("Authorization","Bearer test")).andExpect(status().isUnauthorized());
        assertThat(ok(request(get("/api/auth/me"),teacher)).get("role").asText()).isEqualTo("TEACHER");
    }
    @Test void failedLoginsHaveGenericErrorAndPersistentTemporaryLock() throws Exception {
        for(int i=0;i<5;i++)mvc.perform(request(body(post("/api/auth/login"),new Login(student.user().username(),"wrong-password")),null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_CREDENTIALS"));
        mvc.perform(request(body(post("/api/auth/login"),new Login(student.user().username(),PASSWORD)),null)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT failed_logins FROM app_users WHERE id=?",Integer.class,student.user().id())).isEqualTo(5);
        db.update("UPDATE app_users SET locked_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",student.user().id());
        ok(request(body(post("/api/auth/login"),new Login(student.user().username(),PASSWORD)),null));
        mvc.perform(request(body(post("/api/auth/login"),new Login("unknown_"+unique(),"wrong-password")),null)).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_CREDENTIALS"));
    }
    @Test void passwordResetIsPrivateOneTimeAndRevokesAllSessions() throws Exception {
        var token=new AtomicReference<String>();
        doAnswer(call->{token.set(call.getArgument(1));return null;}).when(mail).send(anyString(),anyString());
        var response=ok(request(body(post("/api/auth/password-reset/request"),new ResetRequest(student.user().email())),null));
        var unknown=ok(request(body(post("/api/auth/password-reset/request"),new ResetRequest(unique()+"@example.test")),null));
        assertThat(response).isEqualTo(unknown);assertThat(response.toString()).doesNotContain("token");assertThat(token.get()).hasSize(43);
        assertThat(db.queryForObject("SELECT token_hash FROM password_resets WHERE user_id=?",String.class,student.user().id())).isEqualTo(AuthService.hash(token.get()));
        String newPassword="New-password-456";
        ok(request(body(post("/api/auth/password-reset/confirm"),new ResetConfirm(token.get(),newPassword)),null));
        mvc.perform(request(get("/api/auth/me"),student)).andExpect(status().isUnauthorized());
        mvc.perform(request(body(post("/api/auth/password-reset/confirm"),new ResetConfirm(token.get(),newPassword)),null)).andExpect(status().isBadRequest());
        mvc.perform(request(body(post("/api/auth/login"),new Login(student.user().username(),PASSWORD)),null)).andExpect(status().isUnauthorized());
        ok(request(body(post("/api/auth/login"),new Login(student.user().username(),newPassword)),null));
    }
    @Test void expiredResetAndMailFailureDoNotLeaveUsableResetTokens() throws Exception {
        String code="a".repeat(43);
        db.update("INSERT INTO password_resets VALUES (?,?,DATEADD('SECOND',-1,CURRENT_TIMESTAMP),NULL,CURRENT_TIMESTAMP)",AuthService.hash(code),student.user().id());
        mvc.perform(request(body(post("/api/auth/password-reset/confirm"),new ResetConfirm(code,"New-password-456")),null)).andExpect(status().isBadRequest());
        doThrow(new MailSendException("test delivery failure")).when(mail).send(anyString(),anyString());
        ok(request(body(post("/api/auth/password-reset/request"),new ResetRequest(otherStudent.user().email())),null));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM password_resets WHERE user_id=?",Integer.class,otherStudent.user().id())).isZero();
    }
    @Test void requestValidationAndResetRateLimits() throws Exception {
        var invalid=studentBody("bad!", "not-email");
        mvc.perform(request(body(post("/api/auth/signup/student"),invalid),null)).andExpect(status().isBadRequest());
        mvc.perform(request(body(post("/api/auth/refresh"),Map.of("refreshToken","short")),null)).andExpect(status().isBadRequest());
        for(int i=0;i<5;i++)ok(request(body(post("/api/auth/password-reset/request"),new ResetRequest(unique()+"@example.test")),null));
        mvc.perform(request(body(post("/api/auth/password-reset/request"),new ResetRequest(unique()+"@example.test")),null)).andExpect(status().isTooManyRequests());
    }
    @Test void disabledUserAndUnconfiguredEmailAreHandled() throws Exception {
        db.update("UPDATE app_users SET user_status='DISABLED' WHERE id=?",student.user().id());
        mvc.perform(request(get("/api/auth/me"),student)).andExpect(status().isUnauthorized());
        mvc.perform(request(body(post("/api/auth/refresh"),new Refresh(student.refreshToken())),null)).andExpect(status().isUnauthorized());
        doThrow(new ApiError.Failure(HttpStatus.SERVICE_UNAVAILABLE,"MAIL_NOT_CONFIGURED","메일 설정 필요")).when(mail).checkConfigured();
        mvc.perform(request(body(post("/api/auth/password-reset/request"),new ResetRequest(teacher.user().email())),null)).andExpect(status().isServiceUnavailable());
    }
}
