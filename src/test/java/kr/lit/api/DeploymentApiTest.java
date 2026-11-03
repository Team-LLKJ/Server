package kr.lit.api;

import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeploymentApiTest extends AuthTestSupport {
 @Test void readinessIsPublicButDoesNotExposeDatabaseDetails() throws Exception {
  mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
   .andExpect(jsonPath("status").value("UP")).andExpect(jsonPath("components").doesNotExist());
  mvc.perform(request(get("/actuator/env"),teacher)).andExpect(status().isForbidden());
 }
 @Test void allowedFrontendCanPreflightParticipantHeader() throws Exception {
  mvc.perform(options("/api/evaluations/example/questions")
   .header("Origin","https://frontend.example.test").header("Access-Control-Request-Method","GET")
   .header("Access-Control-Request-Headers","X-Participant-Token"))
   .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://frontend.example.test"));
 }
 @Test void otherFrontendOriginIsRejected() throws Exception {
  mvc.perform(options("/api/auth/login").header("Origin","https://other.example.test")
   .header("Access-Control-Request-Method","POST")).andExpect(status().isForbidden());
 }
}
