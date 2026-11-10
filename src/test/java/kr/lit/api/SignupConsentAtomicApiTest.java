package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SignupConsentAtomicApiTest extends AuthTestSupport {
    @MockitoSpyBean ConsentRepository repository;
    @Test void consentStorageFailureRollsBackAccountAndVerificationTogether()throws Exception {
        String email=unique()+"@example.test",proof=createVerifiedEmail(email);
        var input=Map.of("username","s_"+unique(),"email",email,"password",PASSWORD,"name","원자적 가입","emailVerificationToken",proof,"termsVersion","v1","termsAccepted",true,"privacyVersion","v1","privacyAccepted",true);
        doThrow(new IllegalStateException("test-storage-failure")).when(repository).insertConsent(anyString(),eq("PRIVACY_POLICY"),anyString(),any());
        assertThatThrownBy(()->mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input))).hasRootCauseMessage("test-storage-failure");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE email=?",Integer.class,email)).isZero();reset(repository);
        mvc.perform(body(request(post("/api/auth/signup/teacher"),null),input)).andExpect(status().isCreated());
    }
}
