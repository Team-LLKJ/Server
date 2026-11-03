package kr.lit.auth;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static kr.lit.auth.AuthModels.*;
@RestController
@RequestMapping("/api/users/me")
public class AccountDeletionController {
    private final AccountDeletionService service;
    public AccountDeletionController(AccountDeletionService service) {this.service=service;}
    @DeleteMapping Message deleteAccount(@AuthenticationPrincipal Principal principal,@Valid @RequestBody AccountDeletionRequest request) {
        service.deleteAccount(principal,request.currentPassword());
        return new Message("회원탈퇴가 완료되었습니다. 본인 소유 평가와 학생 답안이 삭제되었습니다.");
    }
}
