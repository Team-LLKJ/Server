package kr.lit.auth;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class AuthModels {
    private AuthModels() {}
    public enum Role { TEACHER, STUDENT }
    public record Principal(String userId, Role role, String sessionId) {}
    public record TeacherSignup(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_]{4,30}") String username,
        @NotBlank @Size(min=10,max=128) String password,
        @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=50) String name,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String emailVerificationToken) {}
    public record Login(@NotBlank @Size(max=100) String username, @NotBlank @Size(max=128) String password) {}
    public record Refresh(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String refreshToken) {}
    public record Invite(@Min(1) @Max(168) int expiresInHours) {}
    public record User(String id, String username, String email, String name, String school, Role role,
                       String status, Integer grade, Integer classNumber, Integer studentNumber, Instant createdAt) {}
    public record Tokens(String tokenType, String accessToken, Instant accessExpiresAt,
                         String refreshToken, Instant refreshExpiresAt, User user) {}
    public record Invitation(String inviteCode, String school, Instant expiresAt) {}
    public record Message(String message) {}
    public record UpdateMe(@NotBlank @Size(max=50) String name) {}
    public record ChangePassword(@NotBlank @Size(max=128) String currentPassword, @NotBlank @Size(min=10,max=128) String newPassword) {}
}
