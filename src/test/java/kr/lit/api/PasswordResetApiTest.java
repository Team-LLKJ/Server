package kr.lit.api;

import kr.lit.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordResetApiTest extends AuthTestSupport {
    @MockitoBean PasswordResetDeliveryService delivery;
    @Autowired PasswordResetService resets;
    static final String NEXT_PASSWORD="New-password-456";
    String sendReset() throws Exception {
        mvc.perform(body(request(post("/api/auth/password-reset/request"),null),Map.of("email",teacher.user().email().toUpperCase(Locale.ROOT))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("token").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        var token=ArgumentCaptor.forClass(String.class);
        verify(delivery,atLeastOnce()).sendResetLink(eq(teacher.user().email()),token.capture());
        return token.getValue();
    }
    void confirmReset(String token) throws Exception {
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",token,"newPassword",NEXT_PASSWORD)))
                .andExpect(status().isOk());
    }
    @Test void resetsPasswordOnceAndRevokesEverySessionWithoutChangingOtherAccounts() throws Exception {
        var other=newTeacher();var second=auth.login(new AuthModels.Login(teacher.user().username(),PASSWORD));
        String token=sendReset();assertThat(token).hasSize(43);
        assertThat(db.queryForObject("SELECT token_hash FROM password_reset_tokens WHERE user_id=?",String.class,teacher.user().id())).isEqualTo(AuthService.hash(token));
        confirmReset(token);
        for(var session:List.of(teacher,second)) {
            mvc.perform(request(get("/api/auth/me"),session)).andExpect(status().isUnauthorized());
            mvc.perform(body(request(post("/api/auth/refresh"),null),Map.of("refreshToken",session.refreshToken()))).andExpect(status().isUnauthorized());
        }
        mvc.perform(request(get("/api/auth/me"),other)).andExpect(status().isOk());
        mvc.perform(body(request(post("/api/auth/login"),null),Map.of("username",teacher.user().username(),"password",PASSWORD))).andExpect(status().isUnauthorized());
        mvc.perform(body(request(post("/api/auth/login"),null),Map.of("username",teacher.user().username(),"password",NEXT_PASSWORD))).andExpect(status().isOk());
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",token,"newPassword","Another-password-789")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("PASSWORD_RESET_USED"));
    }
    @Test void unknownInactiveAndStudentEmailsHaveSameResponseAndCooldownDoesNotResend() throws Exception {
        var known=ok(body(request(post("/api/auth/password-reset/request"),null),Map.of("email",teacher.user().email())));
        var missing=ok(body(request(post("/api/auth/password-reset/request"),null),Map.of("email",unique()+"@example.test")));
        assertThat(missing).isEqualTo(known);
        var repeated=ok(body(request(post("/api/auth/password-reset/request"),null),Map.of("email",teacher.user().email())));
        assertThat(repeated).isEqualTo(known);
        verify(delivery,times(1)).sendResetLink(eq(teacher.user().email()),anyString());
        var inactive=newTeacher();db.update("UPDATE app_users SET user_status='INACTIVE' WHERE id=?",inactive.user().id());
        var student=newTeacher();db.update("UPDATE app_users SET user_role='STUDENT' WHERE id=?",student.user().id());
        for(var user:List.of(inactive,student))assertThat(ok(body(request(post("/api/auth/password-reset/request"),null),Map.of("email",user.user().email())))).isEqualTo(known);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id IN (?,?)",Integer.class,inactive.user().id(),student.user().id())).isZero();
    }
    @Test void distinguishesExpiredInvalidAndReplacedTokens() throws Exception {
        String token=sendReset();
        db.update("UPDATE password_reset_tokens SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP),created_at=DATEADD('SECOND',-61,CURRENT_TIMESTAMP) WHERE user_id=?",teacher.user().id());
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",token,"newPassword",NEXT_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("PASSWORD_RESET_EXPIRED"));
        String replacement=sendReset();assertThat(replacement).isNotEqualTo(token);
        for(String invalid:List.of(token,"x".repeat(43)))mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",invalid,"newPassword",NEXT_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("PASSWORD_RESET_INVALID"));
        confirmReset(replacement);
    }
    @Test void invalidPasswordDoesNotConsumeTokenAndExistingPasswordChangeInvalidatesIt() throws Exception {
        String token=sendReset();
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",token,"newPassword","short"))).andExpect(status().isBadRequest());
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",token,"newPassword",PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("PASSWORD_UNCHANGED"));
        assertThat(db.queryForObject("SELECT used_at FROM password_reset_tokens WHERE token_hash=?",java.time.OffsetDateTime.class,AuthService.hash(token))).isNull();
        auth.changePassword(auth.authenticate(teacher.accessToken()),PASSWORD,NEXT_PASSWORD);
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",token,"newPassword","Another-password-789")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("PASSWORD_RESET_INVALID"));
    }
    @Test void emailChangeInvalidatesPreviouslyIssuedResetLink() throws Exception {
        String token=sendReset();
        db.update("UPDATE app_users SET email=? WHERE id=?",unique()+"@example.test",teacher.user().id());
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",token,"newPassword",NEXT_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("PASSWORD_RESET_INVALID"));
        assertThat(auth.login(new AuthModels.Login(teacher.user().username(),PASSWORD)).user().id()).isEqualTo(teacher.user().id());
    }
    @Test void accountDeletionRemovesResetLinksAndPreventsRecovery() throws Exception {
        String token=sendReset();
        mvc.perform(body(request(delete("/api/users/me"),teacher),Map.of("currentPassword",PASSWORD,"deleteOwnedData",true)))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id=?",Integer.class,teacher.user().id())).isZero();
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token",token,"newPassword",NEXT_PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("PASSWORD_RESET_INVALID"));
    }
    @Test void failedDeliveryRollsBackAndPreservesPreviousLink() throws Exception {
        String token=sendReset();
        db.update("UPDATE password_reset_tokens SET created_at=DATEADD('SECOND',-61,CURRENT_TIMESTAMP) WHERE user_id=?",teacher.user().id());
        doThrow(new ApiError.Failure(org.springframework.http.HttpStatus.BAD_GATEWAY,"EMAIL_DELIVERY_FAILED","메일 오류"))
                .when(delivery).sendResetLink(eq(teacher.user().email()),anyString());
        mvc.perform(body(request(post("/api/auth/password-reset/request"),null),Map.of("email",teacher.user().email()))).andExpect(status().isBadGateway());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id=?",Integer.class,teacher.user().id())).isEqualTo(1);
        confirmReset(token);
    }
    @Test void concurrentConfirmationsOnlyChangePasswordOnceAndUnlockLogin() throws Exception {
        String token=sendReset();
        db.update("UPDATE app_users SET failed_logins=5,locked_until=DATEADD('MINUTE',15,CURRENT_TIMESTAMP) WHERE id=?",teacher.user().id());
        var gate=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        Callable<Boolean> confirm=()->{gate.await();try{resets.confirmPasswordReset(token,NEXT_PASSWORD);return true;}catch(ApiError.Failure failure){return false;}};
        try {
            var first=executor.submit(confirm);var second=executor.submit(confirm);gate.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }finally{executor.shutdownNow();}
        assertThat(auth.login(new AuthModels.Login(teacher.user().username(),NEXT_PASSWORD)).user().id()).isEqualTo(teacher.user().id());
    }
    @Test void requestValidationAndIpRateLimitApplyWithoutAuthentication() throws Exception {
        mvc.perform(body(request(post("/api/auth/password-reset/request"),null),Map.of("email","invalid"))).andExpect(status().isBadRequest());
        mvc.perform(body(request(post("/api/auth/password-reset/confirm"),null),Map.of("token","short","newPassword",NEXT_PASSWORD))).andExpect(status().isBadRequest());
        remote="reset-limit-"+unique();
        for(int i=0;i<30;i++)mvc.perform(body(request(post("/api/auth/password-reset/request"),null),Map.of("email",unique()+"@example.test"))).andExpect(status().isAccepted());
        mvc.perform(body(request(post("/api/auth/password-reset/request"),null),Map.of("email",teacher.user().email())))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("code").value("RATE_LIMITED"));
    }
}
