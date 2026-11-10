package kr.lit.api;
import kr.lit.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SignupEmailApiTest extends AuthTestSupport {
    @MockitoBean SignupEmailDeliveryService delivery;
    String sendCode(String email) throws Exception {
        mvc.perform(body(request(post("/api/auth/email-verifications"),null),Map.of("email",email))).andExpect(status().isOk()).andExpect(jsonPath("resendAfterSeconds").value(60)).andExpect(jsonPath("code").doesNotExist());
        var code=ArgumentCaptor.forClass(String.class);verify(delivery).sendVerificationCode(eq(email.toLowerCase(Locale.ROOT)),code.capture());return code.getValue();
    }
    @Test void emailCodeConfirmationAndSignupWithoutSchoolOrInvite() throws Exception {
        String email=unique()+"@example.test",code=sendCode(email);
        assertThat(db.queryForObject("SELECT code_hash FROM signup_email_verifications WHERE email=?",String.class,email)).isNotEqualTo(code);
        var confirmed=ok(body(request(post("/api/auth/email-verifications/confirm"),null),Map.of("email",email.toUpperCase(Locale.ROOT),"code",code)));
        String token=confirmed.path("emailVerificationToken").asText();assertThat(token).hasSize(43);
        assertThat(db.queryForObject("SELECT token_hash FROM signup_email_verifications WHERE email=?",String.class,email)).isEqualTo(AuthService.hash(token));
        var input=new HashMap<String,Object>(Map.of("username","u_"+unique(),"email",email,"password","Test123!","name","이메일 사용자","emailVerificationToken",token,"termsVersion","v1","termsAccepted",true,"privacyVersion","v1","privacyAccepted",true));
        for(String invalidPassword:List.of("Test12!","Test123!"+"x".repeat(13))) {
            input.put("password",invalidPassword);
            mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        }
        input.put("password","Test123!");
        var user=ok(body(request(post("/api/auth/signup/teacher"),null),input));
        assertThat(user.path("school").asText()).isEmpty();assertThat(user.path("role").asText()).isEqualTo("TEACHER");
    }
    @Test void unverifiedOrDifferentEmailCannotSignup() throws Exception {
        String email=unique()+"@example.test",token=createVerifiedEmail(email);
        var input=new HashMap<String,Object>(Map.of("username","u_"+unique(),"email",unique()+"@example.test","password",PASSWORD,"name","사용자","emailVerificationToken",token,"termsVersion","v1","termsAccepted",true,"privacyVersion","v1","privacyAccepted",true));
        mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("EMAIL_VERIFICATION_REQUIRED"));
        input.remove("emailVerificationToken");input.put("school","임의학교");input.put("inviteCode","x".repeat(43));
        mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
    }
    @Test void resendAndAttemptLimitsExpireAndInvalidatePreviousCode() throws Exception {
        String email=unique()+"@example.test",code=sendCode(email);
        mvc.perform(body(request(post("/api/auth/email-verifications"),null),Map.of("email",email))).andExpect(status().isTooManyRequests()).andExpect(jsonPath("code").value("EMAIL_RESEND_LIMITED"));
        String wrong=code.equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++)mvc.perform(body(request(post("/api/auth/email-verifications/confirm"),null),Map.of("email",email,"code",wrong))).andExpect(status().isBadRequest());
        mvc.perform(body(request(post("/api/auth/email-verifications/confirm"),null),Map.of("email",email,"code",code))).andExpect(status().isTooManyRequests()).andExpect(jsonPath("code").value("EMAIL_ATTEMPTS_EXCEEDED"));
        assertThat(db.queryForObject("SELECT attempts FROM signup_email_verifications WHERE email=?",Integer.class,email)).isEqualTo(5);
        db.update("UPDATE signup_email_verifications SET last_sent_at=DATEADD('SECOND',-61,CURRENT_TIMESTAMP),code_expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE email=?",email);
        mvc.perform(body(request(post("/api/auth/email-verifications/confirm"),null),Map.of("email",email,"code",code))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("EMAIL_CODE_EXPIRED"));
        mvc.perform(body(request(post("/api/auth/email-verifications"),null),Map.of("email",email))).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT attempts FROM signup_email_verifications WHERE email=?",Integer.class,email)).isZero();
    }
    @Test void expiredAndConsumedTicketCannotRegisterAgain() throws Exception {
        String email=unique()+"@example.test",token=createVerifiedEmail(email);
        db.update("UPDATE signup_email_verifications SET token_expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE email=?",email);
        mvc.perform(body(request(post("/api/auth/signup/teacher"),null),Map.of("username","u_"+unique(),"email",email,"password",PASSWORD,"name","사용자","emailVerificationToken",token,"termsVersion","v1","termsAccepted",true,"privacyVersion","v1","privacyAccepted",true))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("EMAIL_VERIFICATION_REQUIRED"));
    }
    @Test void deliveryFailureNeverGrantsVerificationAndCanRetry() throws Exception {
        String email=unique()+"@example.test";
        doThrow(new ApiError.Failure(org.springframework.http.HttpStatus.BAD_GATEWAY,"EMAIL_DELIVERY_FAILED","발송 실패")).when(delivery).sendVerificationCode(eq(email),anyString());
        mvc.perform(body(request(post("/api/auth/email-verifications"),null),Map.of("email",email))).andExpect(status().isBadGateway());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM signup_email_verifications WHERE email=?",Integer.class,email)).isZero();
    }
    @Test void usernameAvailabilityIsPublicNormalizedAndSignupStillChecksUniqueness() throws Exception {
        mvc.perform(request(get("/api/auth/username-availability").param("username",teacher.user().username().toUpperCase(Locale.ROOT)),null)).andExpect(status().isOk()).andExpect(jsonPath("available").value(false));
        mvc.perform(request(get("/api/auth/username-availability").param("username","free_"+unique()),null)).andExpect(status().isOk()).andExpect(jsonPath("available").value(true));
        mvc.perform(request(get("/api/auth/username-availability").param("username","a"),null)).andExpect(status().isBadRequest());
        mvc.perform(body(request(post("/api/auth/email-verifications"),null),Map.of("email",teacher.user().email()))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("EMAIL_ALREADY_EXISTS"));
    }
    @Test void resendInvalidatesPreviouslyConfirmedSignupToken() throws Exception {
        String email=unique()+"@example.test",code=sendCode(email);
        var result=ok(body(request(post("/api/auth/email-verifications/confirm"),null),Map.of("email",email,"code",code)));
        mvc.perform(body(request(post("/api/auth/email-verifications/confirm"),null),Map.of("email",email,"code",code))).andExpect(status().isBadRequest());
        db.update("UPDATE signup_email_verifications SET last_sent_at=DATEADD('SECOND',-61,CURRENT_TIMESTAMP) WHERE email=?",email);
        mvc.perform(body(request(post("/api/auth/email-verifications"),null),Map.of("email",email))).andExpect(status().isOk());
        mvc.perform(body(request(post("/api/auth/signup/teacher"),null),Map.of("username","u_"+unique(),"email",email,"password",PASSWORD,"name","사용자","emailVerificationToken",result.path("emailVerificationToken").asText(),"termsVersion","v1","termsAccepted",true,"privacyVersion","v1","privacyAccepted",true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("EMAIL_VERIFICATION_REQUIRED"));
    }
}
