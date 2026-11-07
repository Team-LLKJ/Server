package kr.lit.auth;

import jakarta.validation.constraints.*;

public final class PasswordResetModels {
    private PasswordResetModels() {}
    public record PasswordResetRequest(@NotBlank @Email @Size(max=254) String email) {}
    public record PasswordResetConfirmRequest(
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(min=10,max=128) String newPassword) {}
}
