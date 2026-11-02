package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConsentApiTest extends AuthTestSupport {
    private static final String PATH="/api/users/me/consents";
    @Autowired ConsentRepository repository;
    Map<String,Object> consentRequest() {
        return new HashMap<>(Map.of("termsVersion","v1","termsAccepted",true,"privacyVersion","v1","privacyAccepted",true));
    }
    @Test void newAndExistingUsersHaveNoImplicitConsent() throws Exception {
        mvc.perform(request(get(PATH),teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("terms.version").value("v1")).andExpect(jsonPath("terms.accepted").value(false))
                .andExpect(jsonPath("terms.acceptedAt").isEmpty()).andExpect(jsonPath("requiresAcceptance").value(true));
        mvc.perform(request(get(PATH+"/history"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("items").isEmpty());
    }
    @Test void acceptancePersistsBothPoliciesWithServerTimeAndRetryPreservesOriginalTime() throws Exception {
        Instant before=Instant.now();var first=ok(body(request(post(PATH),teacher),consentRequest()));
        assertThat(first.path("requiresAcceptance").asBoolean()).isFalse();
        assertThat(first.path("terms").path("accepted").asBoolean()).isTrue();
        Instant recorded=Instant.parse(first.path("terms").path("acceptedAt").asText());
        assertThat(recorded).isBetween(before,Instant.now());
        var again=ok(body(request(post(PATH),teacher),consentRequest()));assertThat(again).isEqualTo(first);
        assertThat(repository.findConsentHistory(teacher.user().id())).hasSize(2);
        assertThat(ok(request(get(PATH+"/history"),teacher)).path("items").size()).isEqualTo(2);
        assertThat(ok(request(get(PATH),teacher))).isEqualTo(first);
    }
    @Test void refusalAndMissingFlagsDoNotWriteAnyRecords() throws Exception {
        for(String flag:List.of("termsAccepted","privacyAccepted")) {
            var input=consentRequest();input.put(flag,false);
            mvc.perform(body(request(post(PATH),teacher),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("CONSENT_REQUIRED"));
            input.remove(flag);
            mvc.perform(body(request(post(PATH),teacher),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        }
        assertThat(repository.findConsentHistory(teacher.user().id())).isEmpty();
    }
    @Test void staleOrUnknownVersionRejectsBothPoliciesAtomically() throws Exception {
        for(String version:List.of("termsVersion","privacyVersion")) {
            var input=consentRequest();input.put(version,"outdated");
            mvc.perform(body(request(post(PATH),teacher),input)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("POLICY_VERSION_MISMATCH"));
        }
        assertThat(repository.findConsentHistory(teacher.user().id())).isEmpty();
    }
    @Test void accountIsolationAndClientCannotChooseAnotherUserOrAcceptanceTime() throws Exception {
        var other=newTeacher();var input=consentRequest();input.put("userId",other.user().id());input.put("acceptedAt","2000-01-01T00:00:00Z");
        ok(body(request(post(PATH),teacher),input));
        assertThat(repository.findConsentHistory(other.user().id())).isEmpty();
        mvc.perform(request(get(PATH),other)).andExpect(status().isOk()).andExpect(jsonPath("requiresAcceptance").value(true));
        mvc.perform(request(get(PATH+"/history"),other)).andExpect(status().isOk()).andExpect(jsonPath("items").isEmpty());
        assertThat(repository.findConsentHistory(teacher.user().id()).get(0).acceptedAt()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
    }
    @Test void authenticationIsRequiredForAllConsentRoutes() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH+"/history")).andExpect(status().isUnauthorized());
        mvc.perform(body(post(PATH),consentRequest())).andExpect(status().isUnauthorized());
    }
    @Test void changingOnePolicyVersionRequiresOnlyThatNewVersionAndPreservesHistory() throws Exception {
        ok(body(request(post(PATH),teacher),consentRequest()));as(teacher);
        var changed=new ConsentService(repository,"v2","v1").getConsents();
        assertThat(changed.terms().accepted()).isFalse();assertThat(changed.privacy().accepted()).isTrue();assertThat(changed.requiresAcceptance()).isTrue();
        assertThat(repository.findConsentHistory(teacher.user().id())).hasSize(2);
    }
    @Test void concurrentRetriesProduceOneRecordPerPolicyVersion() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call=()->mvc.perform(body(request(post(PATH),teacher),consentRequest())).andReturn().getResponse().getStatus();
            var futures=pool.invokeAll(List.of(call,call));for(var future:futures)assertThat(future.get()).isEqualTo(200);
        } finally {pool.shutdownNow();}
        assertThat(repository.findConsentHistory(teacher.user().id())).hasSize(2);
    }
}
