package kr.lit.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OpenApiTest extends AuthTestSupport {
    @Test void openApiIsPublicAndDescribesAuthAndExistingRoutes() throws Exception {
        var spec=ok(request(get("/api/openapi"),null));
        assertThat(spec.path("openapi").asText()).startsWith("3.");
        assertThat(spec.path("info").path("title").asText()).isEqualTo("LIT API");
        assertThat(spec.path("servers").get(0).path("url").asText()).isEqualTo("/");
        var schemes=spec.path("components").path("securitySchemes");
        assertThat(schemes.path("TeacherAccessToken").path("scheme").asText()).isEqualTo("bearer");
        assertThat(schemes.path("ParticipantToken").path("name").asText()).isEqualTo("X-Participant-Token");
        var paths=spec.path("paths");
        assertThat(paths.path("/api/auth/login").path("post").path("security").isEmpty()).isTrue();
        assertThat(paths.path("/api/participation/verify").path("get").path("security").isEmpty()).isTrue();
        assertThat(paths.path("/api/teacher/assessments").path("get").path("security").get(0).has("TeacherAccessToken")).isTrue();
        var student=paths.path("/api/student/evaluations/{id}/workspace").path("get");
        assertThat(student.path("security").get(0).has("ParticipantToken")).isTrue();
        assertThat(student.path("parameters").toString()).doesNotContain("X-Participant-Token");
        assertThat(spec.path("components").path("schemas").has("Login")).isTrue();
        assertThat(paths.toString()).doesNotContain("/actuator/");
    }
    @Test void swaggerRedirectAssetsAndConfigurationStayUnderApiProxy() throws Exception {
        var redirect=mvc.perform(request(get("/api/docs"),null)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).startsWith("/api/swagger-ui/");
        mvc.perform(request(get(redirect),null)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        mvc.perform(request(get("/api/swagger-ui/swagger-ui-bundle.js"),null)).andExpect(status().isOk());
        mvc.perform(request(get("/api/openapi/swagger-config"),null)).andExpect(status().isOk()).andExpect(jsonPath("url").value("/api/openapi"));
    }
    @Test void documentationDoesNotBypassApiAuthentication() throws Exception {
        mvc.perform(request(get("/api/teacher/assessments"),null)).andExpect(status().isUnauthorized());
        mvc.perform(request(get("/api/student/evaluations/missing/workspace"),null)).andExpect(status().isUnauthorized());
        mvc.perform(request(get("/api/users/me/consents"),null)).andExpect(status().isUnauthorized());
    }
}
