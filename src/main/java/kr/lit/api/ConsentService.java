package kr.lit.api;

import kr.lit.auth.AccountAccess;
import kr.lit.auth.AuthModels.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import static kr.lit.api.ConsentModels.*;

@Service
@Transactional
public class ConsentService {
    private static final String TERMS = "TERMS_OF_SERVICE";
    private static final String PRIVACY = "PRIVACY_POLICY";
    private final ConsentRepository repository;
    private final String termsVersion;
    private final String privacyVersion;
    public ConsentService(ConsentRepository repository,
            @Value("${lit.policies.terms-version:v1}") String termsVersion,
            @Value("${lit.policies.privacy-version:v1}") String privacyVersion) {
        if(termsVersion.isBlank()||privacyVersion.isBlank()||termsVersion.length()>64||privacyVersion.length()>64
                ||!termsVersion.equals(termsVersion.strip())||!privacyVersion.equals(privacyVersion.strip()))
            throw new IllegalArgumentException("Policy versions must contain 1 to 64 characters without surrounding whitespace");
        this.repository=repository;this.termsVersion=termsVersion;this.privacyVersion=privacyVersion;
    }
    @Transactional(readOnly=true)
    public ConsentResponse getConsents() {
        String userId=AccountAccess.require(Role.TEACHER).userId();
        return buildResponse(repository.findConsentHistory(userId));
    }
    @Transactional(readOnly=true)
    public ConsentHistoryResponse getConsentHistory() {
        return new ConsentHistoryResponse(repository.findConsentHistory(AccountAccess.require(Role.TEACHER).userId()));
    }
    public ConsentResponse acceptConsents(ConsentRequest request) {
        String userId=AccountAccess.require(Role.TEACHER).userId();
        if(!Boolean.TRUE.equals(request.termsAccepted())||!Boolean.TRUE.equals(request.privacyAccepted()))
            throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"CONSENT_REQUIRED","이용약관과 개인정보 처리방침을 각각 확인하고 동의해주세요.");
        if(!termsVersion.equals(request.termsVersion())||!privacyVersion.equals(request.privacyVersion()))
            throw new ApiError.Failure(HttpStatus.CONFLICT,"POLICY_VERSION_MISMATCH","약관 버전이 변경되었습니다. 최신 내용을 확인하고 다시 동의해주세요.");
        // 두 문서의 저장과 중복 요청을 같은 계정 잠금 안에서 직렬화합니다.
        repository.lockUser(userId);
        var history=repository.findConsentHistory(userId);var now=Instant.now();
        if(!hasConsent(history,TERMS,termsVersion))repository.insertConsent(userId,TERMS,termsVersion,now);
        if(!hasConsent(history,PRIVACY,privacyVersion))repository.insertConsent(userId,PRIVACY,privacyVersion,now);
        return buildResponse(repository.findConsentHistory(userId));
    }
    private static boolean hasConsent(List<ConsentRecordResponse> history,String type,String version) {
        return history.stream().anyMatch(item->item.policyType().equals(type)&&item.version().equals(version));
    }
    private static PolicyConsentResponse buildPolicyResponse(List<ConsentRecordResponse> history,String type,String version) {
        Instant acceptedAt=history.stream().filter(item->item.policyType().equals(type)&&item.version().equals(version))
                .map(ConsentRecordResponse::acceptedAt).findFirst().orElse(null);
        return new PolicyConsentResponse(version,acceptedAt!=null,acceptedAt);
    }
    private ConsentResponse buildResponse(List<ConsentRecordResponse> history) {
        var terms=buildPolicyResponse(history,TERMS,termsVersion);var privacy=buildPolicyResponse(history,PRIVACY,privacyVersion);
        return new ConsentResponse(terms,privacy,!terms.accepted()||!privacy.accepted());
    }
}
