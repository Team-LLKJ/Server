package kr.lit.auth;
import jakarta.validation.constraints.*;
import java.time.Instant;
public final class SignupEmailModels {
    private SignupEmailModels() {}
    public record EmailVerificationRequest(@NotBlank @Email @Size(max=254) String email) {}
    public record EmailConfirmationRequest(@NotBlank @Email @Size(max=254) String email,
            @NotBlank @Pattern(regexp="[0-9]{6}") String code) {}
    public record EmailVerificationResponse(Instant expiresAt,int resendAfterSeconds) {}
    public record EmailConfirmationResponse(String emailVerificationToken,Instant expiresAt) {}
    public record UsernameAvailabilityResponse(String username,boolean available) {}
}
