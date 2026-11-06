package kr.lit.auth;
import jakarta.validation.constraints.*;
import java.time.Instant;
public final class EmailChangeModels {
    private EmailChangeModels() {}
    public record EmailChangeRequest(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=128) String currentPassword) {}
    public record EmailConfirmRequest(@NotBlank @Email @Size(max=254) String email,@NotBlank @Pattern(regexp="[0-9]{6}") String code,@NotBlank @Size(max=128) String currentPassword) {}
    public record EmailChangeResponse(Instant expiresAt,int resendAfterSeconds) {}
}
