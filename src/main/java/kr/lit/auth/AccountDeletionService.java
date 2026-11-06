package kr.lit.auth;
import kr.lit.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static kr.lit.auth.AuthModels.*;
@Service
@Transactional
public class AccountDeletionService {
    private final AccountDeletionRepository repository;
    private final PasswordEncoder encoder;
    public AccountDeletionService(AccountDeletionRepository repository,PasswordEncoder encoder) {this.repository=repository;this.encoder=encoder;}
    public void deleteAccount(Principal principal,String currentPassword) {
        var rows=repository.lockAccount(principal.userId());
        if(rows.isEmpty()||!repository.existsSession(principal.userId(),principal.sessionId()))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","다시 로그인해주세요.");
        var account=rows.get(0);
        if(!encoder.matches(currentPassword,(String)account.get("PASSWORD_HASH")))throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_CURRENT_PASSWORD","현재 비밀번호가 올바르지 않습니다.");
        var templates=repository.lockTemplates(principal.userId());var evaluations=repository.lockEvaluations(principal.userId());
        for(String evaluationId:evaluations)if(repository.existsRunningEvaluation(evaluationId))throw new ApiError.Failure(HttpStatus.CONFLICT,"EVALUATION_IN_PROGRESS","진행 중인 평가를 종료한 뒤 탈퇴해주세요.");
        for(String evaluationId:evaluations)repository.deleteEvaluation(evaluationId);
        for(String templateId:templates)repository.deleteTemplate(templateId);
        repository.deleteAccountData(principal.userId(),(String)account.get("EMAIL"));
        // 다른 소유자의 채점 이력 외래키는 유지하되 로그인·식별 정보를 제거합니다.
        repository.anonymizeAccount(principal.userId(),encoder.encode(UUID.randomUUID().toString()));
    }
}
