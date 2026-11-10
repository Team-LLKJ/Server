package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static kr.lit.auth.AuthModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SignupConsentRegistrationApiTest extends AuthTestSupport {
    @Autowired ConsentRepository consents;
    Map<String,Object> input(String email,String proof){return new HashMap<>(Map.of("username","s_"+unique(),"email",email,"password",PASSWORD,"name","가입 사용자","emailVerificationToken",proof,"termsVersion","v1","termsAccepted",true,"privacyVersion","v1","privacyAccepted",true));}
    @Test void publicVersionsAndSignupConsentPreventFirstLoginPrompt()throws Exception {
        mvc.perform(get("/api/policies/current")).andExpect(status().isOk()).andExpect(jsonPath("termsVersion").value("v1")).andExpect(jsonPath("privacyVersion").value("v1"));
        String email=unique()+"@example.test",proof=createVerifiedEmail(email);var input=input(email,proof);var user=ok(body(request(post("/api/auth/signup/teacher"),null),input));
        var tokens=auth.login(new Login((String)input.get("username"),PASSWORD));mvc.perform(request(get("/api/users/me/consents"),tokens)).andExpect(jsonPath("requiresAcceptance").value(false)).andExpect(jsonPath("terms.acceptedAt").isNotEmpty()).andExpect(jsonPath("privacy.acceptedAt").isNotEmpty());
        assertThat(consents.findConsentHistory(user.path("id").asText())).hasSize(2);assertThat(consents.findConsentHistory(user.path("id").asText()).stream().map(ConsentModels.ConsentRecordResponse::acceptedAt).distinct()).hasSize(1);
    }
    @Test void declinedOrStaleConsentDoesNotConsumeVerificationOrCreateAccount()throws Exception {
        String email=unique()+"@example.test",proof=createVerifiedEmail(email);var input=input(email,proof);input.put("termsAccepted",false);
        mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("CONSENT_REQUIRED"));
        input.put("termsAccepted",true);input.put("privacyVersion","outdated");mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("POLICY_VERSION_MISMATCH"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE email=?",Integer.class,email)).isZero();input.put("privacyVersion","v1");mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input)).andExpect(status().isCreated());
    }
    @Test void missingConsentIsNeverImplicitlyAccepted()throws Exception {
        String email=unique()+"@example.test";var input=input(email,createVerifiedEmail(email));input.remove("termsAccepted");mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE email=?",Integer.class,email)).isZero();
    }
}
