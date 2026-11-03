package kr.lit.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class TeacherProfileApiTest extends AuthTestSupport {
    @Test void passwordChangeKeepsCurrentSessionButRevokesOtherSessions() {
        var other=auth.login(new kr.lit.auth.AuthModels.Login(teacher.user().username(),PASSWORD));
        var current=auth.authenticate(teacher.accessToken());
        auth.changePassword(current,PASSWORD,"Changed-password-123");
        assertThat(auth.authenticate(teacher.accessToken()).userId()).isEqualTo(current.userId());
        assertThatThrownBy(()->auth.authenticate(other.accessToken())).isInstanceOf(ApiError.Failure.class);
    }
}
