package kr.lit.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import kr.lit.auth.*;
import static kr.lit.auth.AuthModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordChangeApiTest extends AuthTestSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PasswordChangeRepository repository;
    @Test void changePasswordRevokesOtherAccessAndRefreshButKeepsCurrentFamily() throws Exception {
        var other=auth.login(new Login(teacher.user().username(),PASSWORD));
        mvc.perform(body(request(post("/api/users/me/password"),teacher),Map.of("currentPassword",PASSWORD,"newPassword","Changed-password-123"))).andExpect(status().isOk());
        mvc.perform(request(get("/api/auth/me"),teacher)).andExpect(status().isOk());
        mvc.perform(request(get("/api/auth/me"),other)).andExpect(status().isUnauthorized());
        mvc.perform(body(request(post("/api/auth/refresh"),null),new Refresh(other.refreshToken()))).andExpect(status().isUnauthorized());
        mvc.perform(body(request(post("/api/auth/login"),null),new Login(teacher.user().username(),PASSWORD))).andExpect(status().isUnauthorized());
        mvc.perform(body(request(post("/api/auth/login"),null),new Login(teacher.user().username(),"Changed-password-123"))).andExpect(status().isOk());
        mvc.perform(body(request(post("/api/auth/refresh"),null),new Refresh(teacher.refreshToken()))).andExpect(status().isOk());
    }
    @Test void failuresDoNotChangePasswordOrOtherSessions() throws Exception {
        var other=auth.login(new Login(teacher.user().username(),PASSWORD));
        for(var pair:List.of(Map.of("currentPassword","wrong-password","newPassword","Changed-password-123"),Map.of("currentPassword",PASSWORD,"newPassword",PASSWORD),Map.of("currentPassword",PASSWORD,"newPassword","short")))
            mvc.perform(body(request(post("/api/users/me/password"),teacher),pair)).andExpect(status().isBadRequest());
        mvc.perform(request(get("/api/auth/me"),other)).andExpect(status().isOk());
        assertThat(auth.login(new Login(teacher.user().username(),PASSWORD))).isNotNull();
        mvc.perform(body(request(post("/api/users/me/password"),null),Map.of("currentPassword",PASSWORD,"newPassword","Changed-password-123"))).andExpect(status().isUnauthorized());
    }
    @Test void stalePrincipalCannotChangePasswordAfterLogout() {
        var principal=auth.authenticate(teacher.accessToken());auth.logout(principal);
        assertThatThrownBy(()->auth.changePassword(principal,PASSWORD,"Changed-password-123")).isInstanceOf(ApiError.Failure.class);
        assertThat(auth.login(new Login(teacher.user().username(),PASSWORD))).isNotNull();
    }
    @Test void sessionRevocationFailureRollsBackPasswordHash() {
        var principal=auth.authenticate(teacher.accessToken());
        org.mockito.Mockito.doThrow(new IllegalStateException("test failure")).when(repository).revokeOtherSessions(org.mockito.ArgumentMatchers.eq(teacher.user().id()),org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(()->auth.changePassword(principal,PASSWORD,"Changed-password-123")).isInstanceOf(IllegalStateException.class);
        assertThat(auth.login(new Login(teacher.user().username(),PASSWORD))).isNotNull();
    }
}
