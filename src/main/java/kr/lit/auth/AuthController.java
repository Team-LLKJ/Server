package kr.lit.auth;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import static kr.lit.auth.AuthModels.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) {this.auth=auth;}
    @PostMapping("/signup/student") @ResponseStatus(HttpStatus.CREATED)
    User student(@Valid @RequestBody StudentSignup input) {return auth.signupStudent(input);}
    @PostMapping("/signup/teacher") @ResponseStatus(HttpStatus.CREATED)
    User teacher(@Valid @RequestBody TeacherSignup input) {return auth.signupTeacher(input);}
    @PostMapping("/login") Tokens login(@Valid @RequestBody Login input) {return auth.login(input);}
    @PostMapping("/refresh") Tokens refresh(@Valid @RequestBody Refresh input) {return auth.refresh(input.refreshToken());}
    @GetMapping("/me") User me(@AuthenticationPrincipal Principal p) {return auth.me(p.userId());}
    @PostMapping("/logout") Message logout(@AuthenticationPrincipal Principal p) {auth.logout(p);return new Message("로그아웃되었습니다.");}
    @PostMapping("/teacher-invitations") @ResponseStatus(HttpStatus.CREATED)
    Invitation invite(@Valid @RequestBody Invite input) {return auth.invite(input.expiresInHours());}
    @PostMapping("/password-reset/request") @ResponseStatus(HttpStatus.ACCEPTED)
    Message resetRequest(@Valid @RequestBody ResetRequest input) {
        try {auth.requestReset(input.email());} catch(MailException ignored) { /* same response, no token or email logging */ }
        return new Message("가입된 이메일이라면 비밀번호 재설정 안내를 발송합니다.");
    }
    @PostMapping("/password-reset/confirm") Message reset(@Valid @RequestBody ResetConfirm input) {
        auth.confirmReset(input);return new Message("비밀번호가 변경되었습니다. 다시 로그인해주세요.");
    }
}
