package kr.lit.auth;
import jakarta.validation.constraints.*;
public record AccountDeletionRequest(@NotBlank @Size(max=128) String currentPassword,@AssertTrue boolean deleteOwnedData) {}
