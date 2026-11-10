package kr.lit.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import kr.lit.auth.*;
import static kr.lit.auth.AuthModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NicknameApiTest extends AuthTestSupport {
    @Test void updateNicknamePersistsAndKeepsLegacyNameContract() throws Exception {
        var other=newTeacher();
        mvc.perform(body(request(patch("/api/users/me/nickname"),teacher),Map.of("nickname","  새 별명  "))).andExpect(status().isOk()).andExpect(jsonPath("name").value("새 별명")).andExpect(jsonPath("username").value(teacher.user().username()));
        mvc.perform(request(get("/api/auth/me"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("name").value("새 별명"));
        assertThat(auth.me(other.user().id()).name()).isEqualTo(other.user().name());
        mvc.perform(body(request(patch("/api/users/me"),teacher),Map.of("name","기존 이름 경로"))).andExpect(status().isOk()).andExpect(jsonPath("name").value("기존 이름 경로"));
    }
    @Test void rejectInvalidNamesAndMissingAuthentication() throws Exception {
        for(String nickname:List.of(""," ","가".repeat(51)))mvc.perform(body(request(patch("/api/users/me/nickname"),teacher),Map.of("nickname",nickname))).andExpect(status().isBadRequest());
        mvc.perform(body(request(patch("/api/users/me/nickname"),null),Map.of("nickname","새 이름"))).andExpect(status().isUnauthorized());
        assertThat(auth.me(teacher.user().id()).name()).isEqualTo(teacher.user().name());
    }
    @Test void rejectPrincipalRevokedAfterAuthentication() {
        var principal=auth.authenticate(teacher.accessToken());auth.logout(principal);
        assertThatThrownBy(()->auth.updateName(principal,"새 이름")).isInstanceOf(ApiError.Failure.class);
        assertThat(auth.me(teacher.user().id()).name()).isEqualTo(teacher.user().name());
    }
}
