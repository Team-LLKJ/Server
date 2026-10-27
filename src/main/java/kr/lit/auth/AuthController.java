package kr.lit.auth;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import static kr.lit.auth.AuthModels.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) {this.auth=auth;}
    @PostMapping("/signup/student")
    void studentSignupDisabled() {throw new kr.lit.api.ApiError.Failure(HttpStatus.GONE,"STUDENT_ACCOUNTS_DISABLED","학생은 가입 없이 PIN으로 참여해주세요.");}
    @PostMapping("/signup/teacher") @ResponseStatus(HttpStatus.CREATED)
    User teacher(@Valid @RequestBody TeacherSignup input) {return auth.signupTeacher(input);}
    @PostMapping("/login") Tokens login(@Valid @RequestBody Login input) {return auth.login(input);}
    @PostMapping("/refresh") Tokens refresh(@Valid @RequestBody Refresh input) {return auth.refresh(input.refreshToken());}
    @GetMapping("/me") User me(@AuthenticationPrincipal Principal p) {return auth.me(p.userId());}
    @PostMapping("/logout") Message logout(@AuthenticationPrincipal Principal p) {auth.logout(p);return new Message("로그아웃되었습니다.");}
    @PostMapping("/teacher-invitations") @ResponseStatus(HttpStatus.CREATED)
    Invitation invite(@Valid @RequestBody Invite input) {return auth.invite(input.expiresInHours());}
}
