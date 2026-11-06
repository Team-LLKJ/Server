package kr.lit.auth;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static kr.lit.auth.AuthModels.*;
@RestController
@RequestMapping("/api/users/me/nickname")
public class NicknameController {
    private final AuthService auth;
    public NicknameController(AuthService auth) {this.auth=auth;}
    @PatchMapping User updateNickname(@AuthenticationPrincipal Principal principal,@Valid @RequestBody NicknameChangeRequest request) {
        return auth.updateName(principal,request.nickname());
    }
}
