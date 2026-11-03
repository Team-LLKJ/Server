package kr.lit.api;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ConsentModels {
    private ConsentModels() {}
    public record ConsentRequest(
            @NotBlank @Size(max=64) String termsVersion,
            @NotNull Boolean termsAccepted,
            @NotBlank @Size(max=64) String privacyVersion,
            @NotNull Boolean privacyAccepted) {}
    public record PolicyConsentResponse(String version,boolean accepted,Instant acceptedAt) {}
    public record ConsentResponse(PolicyConsentResponse terms,PolicyConsentResponse privacy,boolean requiresAcceptance) {}
    public record ConsentHistoryResponse(List<ConsentRecordResponse> items) {}
    public record ConsentRecordResponse(String policyType,String version,Instant acceptedAt) {}
}
