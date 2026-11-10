package kr.lit.auth;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static kr.lit.auth.AuthModels.*;
import static kr.lit.auth.EmailChangeModels.*;
@RestController
@RequestMapping("/api/users/me/email")
public class EmailChangeController {
    private final EmailChangeService service;
    public EmailChangeController(EmailChangeService service) {this.service=service;}
    @PostMapping("/verifications") EmailChangeResponse sendVerification(@AuthenticationPrincipal Principal principal,@Valid @RequestBody EmailChangeRequest request) {return service.sendVerification(principal,request);}
    @PostMapping("/confirm") User confirmEmail(@AuthenticationPrincipal Principal principal,@Valid @RequestBody EmailConfirmRequest request) {return service.confirmEmail(principal,request);}
}
