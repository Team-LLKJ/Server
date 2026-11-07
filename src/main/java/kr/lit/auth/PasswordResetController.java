package kr.lit.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static kr.lit.auth.PasswordResetModels.*;

@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {
    private final PasswordResetService resets;
    public PasswordResetController(PasswordResetService resets) {this.resets=resets;}
    @PostMapping("/request") @ResponseStatus(HttpStatus.ACCEPTED)
    public AuthModels.Message requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        resets.requestPasswordReset(request.email());
        return new AuthModels.Message("가입된 이메일이면 비밀번호 재설정 안내를 발송했습니다. 메일함을 확인해주세요.");
    }
    @PostMapping("/confirm")
    public AuthModels.Message confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        resets.confirmPasswordReset(request.token(),request.newPassword());
        return new AuthModels.Message("비밀번호가 변경되었습니다. 새 비밀번호로 다시 로그인해주세요.");
    }
}
