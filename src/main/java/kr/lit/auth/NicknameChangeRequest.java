package kr.lit.auth;
import jakarta.validation.constraints.*;
public record NicknameChangeRequest(@NotBlank @Size(max=50) String nickname) {}
