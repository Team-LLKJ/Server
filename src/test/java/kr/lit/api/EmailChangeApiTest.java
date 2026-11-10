package kr.lit.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import kr.lit.auth.*;
import static kr.lit.auth.AuthModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
class EmailChangeApiTest extends AuthTestSupport {
    @MockitoBean EmailChangeDeliveryService delivery;
    String sendCode(Tokens user,String email) throws Exception {
        mvc.perform(body(request(post("/api/users/me/email/verifications"),user),Map.of("email",email,"currentPassword",PASSWORD))).andExpect(status().isOk()).andExpect(jsonPath("code").doesNotExist());
        var captor=ArgumentCaptor.forClass(String.class);verify(delivery,atLeastOnce()).sendVerificationCode(eq(email.toLowerCase(Locale.ROOT)),captor.capture());return captor.getValue();
    }
    org.springframework.test.web.servlet.ResultActions confirm(Tokens user,String email,String code) throws Exception {
        return mvc.perform(body(request(post("/api/users/me/email/confirm"),user),Map.of("email",email,"code",code,"currentPassword",PASSWORD)));
    }
    @Test void changeEmailRequiresOwnedCodeAndPreservesLogin() throws Exception {
        String email=unique()+"@example.test",code=sendCode(teacher,email.toUpperCase(Locale.ROOT));
        assertThat(auth.me(teacher.user().id()).email()).isEqualTo(teacher.user().email());
        assertThat(db.queryForObject("SELECT code_hash FROM email_change_verifications WHERE user_id=?",String.class,teacher.user().id())).isNotEqualTo(code);
        confirm(teacher,email,code).andExpect(status().isOk()).andExpect(jsonPath("email").value(email));
        confirm(teacher,email,code).andExpect(status().isBadRequest());
        mvc.perform(request(get("/api/auth/me"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("email").value(email));
        assertThat(auth.login(new Login(teacher.user().username(),PASSWORD)).user().email()).isEqualTo(email);
    }
    @Test void wrongCodesCountPersistAndOtherUsersCannotConsume() throws Exception {
        String email=unique()+"@example.test",code=sendCode(teacher,email);var other=newTeacher();
        confirm(other,email,code).andExpect(status().isBadRequest());
        String wrong=code.equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++)confirm(teacher,email,wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_EMAIL_CODE"));
        confirm(teacher,email,code).andExpect(status().isTooManyRequests());
        assertThat(auth.me(teacher.user().id()).email()).isEqualTo(teacher.user().email());
    }
    @Test void resendFailurePreservesPriorChallengeAndSuccessfulResendInvalidatesIt() throws Exception {
        String email=unique()+"@example.test",code=sendCode(teacher,email);
        mvc.perform(body(request(post("/api/users/me/email/verifications"),teacher),Map.of("email",unique()+"@example.test","currentPassword",PASSWORD))).andExpect(status().isTooManyRequests());
        db.update("UPDATE email_change_verifications SET last_sent_at=DATEADD('SECOND',-61,CURRENT_TIMESTAMP) WHERE user_id=?",teacher.user().id());
        String replacement=unique()+"@example.test";
        doThrow(new ApiError.Failure(org.springframework.http.HttpStatus.BAD_GATEWAY,"EMAIL_DELIVERY_FAILED","발송 실패")).when(delivery).sendVerificationCode(eq(replacement),anyString());
        mvc.perform(body(request(post("/api/users/me/email/verifications"),teacher),Map.of("email",replacement,"currentPassword",PASSWORD))).andExpect(status().isBadGateway());
        assertThat(db.queryForObject("SELECT email FROM email_change_verifications WHERE user_id=?",String.class,teacher.user().id())).isEqualTo(email);
        doNothing().when(delivery).sendVerificationCode(eq(replacement),anyString());
        String newCode=sendCode(teacher,replacement);
        confirm(teacher,email,code).andExpect(status().isBadRequest());
        confirm(teacher,replacement,newCode).andExpect(status().isOk());
    }
    @Test void expiryPasswordChangeAndSignupTokensCannotBeUsed() throws Exception {
        String email=unique()+"@example.test",code=sendCode(teacher,email);
        db.update("UPDATE email_change_verifications SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE user_id=?",teacher.user().id());
        confirm(teacher,email,code).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("EMAIL_CODE_EXPIRED"));
        String otherEmail=unique()+"@example.test";createVerifiedEmail(otherEmail);
        confirm(newTeacher(),otherEmail,"012345").andExpect(status().isBadRequest());
        db.update("UPDATE email_change_verifications SET expires_at=DATEADD('MINUTE',10,CURRENT_TIMESTAMP) WHERE user_id=?",teacher.user().id());
        auth.changePassword(auth.authenticate(teacher.accessToken()),PASSWORD,"Changed-password-123");
        mvc.perform(body(request(post("/api/users/me/email/confirm"),teacher),Map.of("email",email,"code",code,"currentPassword","Changed-password-123"))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("EMAIL_CODE_EXPIRED"));
    }
    @Test void duplicateAtConfirmationIsRejectedWithoutConsumingChallenge() throws Exception {
        String email=unique()+"@example.test",code=sendCode(teacher,email);var other=newTeacher();
        db.update("UPDATE app_users SET email=? WHERE id=?",email,other.user().id());
        confirm(teacher,email,code).andExpect(status().isConflict()).andExpect(jsonPath("code").value("EMAIL_ALREADY_EXISTS"));
        assertThat(auth.me(teacher.user().id()).email()).isEqualTo(teacher.user().email());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM email_change_verifications WHERE user_id=?",Integer.class,teacher.user().id())).isEqualTo(1);
    }
    @Test void invalidPasswordDuplicateAddressAndMissingLoginAreRejected() throws Exception {
        var other=newTeacher();
        mvc.perform(body(request(post("/api/users/me/email/verifications"),teacher),Map.of("email",other.user().email(),"currentPassword",PASSWORD))).andExpect(status().isConflict());
        mvc.perform(body(request(post("/api/users/me/email/verifications"),teacher),Map.of("email",teacher.user().email(),"currentPassword",PASSWORD))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("EMAIL_UNCHANGED"));
        mvc.perform(body(request(post("/api/users/me/email/verifications"),teacher),Map.of("email",unique()+"@example.test","currentPassword","wrong"))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_CURRENT_PASSWORD"));
        mvc.perform(body(request(post("/api/users/me/email/verifications"),null),Map.of("email",unique()+"@example.test","currentPassword",PASSWORD))).andExpect(status().isUnauthorized());
        verifyNoInteractions(delivery);
    }
}
