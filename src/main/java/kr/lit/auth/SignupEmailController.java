package kr.lit.auth;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static kr.lit.auth.SignupEmailModels.*;
@RestController
@RequestMapping("/api/auth")
public class SignupEmailController {
    private final SignupEmailService emails;
    public SignupEmailController(SignupEmailService emails) {this.emails=emails;}
    @PostMapping("/email-verifications") public EmailVerificationResponse sendVerification(@Valid @RequestBody EmailVerificationRequest request) {return emails.sendVerification(request.email());}
    @PostMapping("/email-verifications/confirm") public EmailConfirmationResponse confirmEmail(@Valid @RequestBody EmailConfirmationRequest request) {return emails.confirmEmail(request.email(),request.code());}
    @GetMapping("/username-availability") public UsernameAvailabilityResponse getUsernameAvailability(@RequestParam String username) {return emails.getUsernameAvailability(username);}
}
