package kr.lit.auth;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static kr.lit.auth.AuthModels.*;

@RestController
@RequestMapping("/api/users/me")
public class UserController {
    private final AuthService auth;
    public UserController(AuthService auth) { this.auth=auth; }
    @GetMapping User me(@AuthenticationPrincipal Principal p) { return auth.me(p.userId()); }
    @PatchMapping User update(@AuthenticationPrincipal Principal p,@Valid @RequestBody UpdateMe input) { return auth.updateName(p,input.name()); }
    @PostMapping("/password") Message password(@AuthenticationPrincipal Principal p,@Valid @RequestBody ChangePassword input) {
        auth.changePassword(p,input.currentPassword(),input.newPassword());
        return new Message("비밀번호가 변경되었습니다. 다른 기기의 로그인은 해제되었습니다.");
    }
}
