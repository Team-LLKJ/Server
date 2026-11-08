package kr.lit.auth;
import kr.lit.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.lit.auth.AuthModels.*;
@Service
@Transactional
public class PasswordChangeService {
    private final PasswordChangeRepository repository;
    private final PasswordEncoder encoder;
    public PasswordChangeService(PasswordChangeRepository repository,PasswordEncoder encoder) {this.repository=repository;this.encoder=encoder;}
    public void changePassword(Principal principal,String currentPassword,String newPassword) {
        if(currentPassword==null||currentPassword.isBlank()||currentPassword.length()>128||newPassword==null||newPassword.isBlank()||newPassword.length()<8||newPassword.length()>128)
            throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","비밀번호 형식과 길이를 확인해주세요.");
        var hashes=repository.lockPassword(principal.userId());
        var families=repository.getSessionFamily(principal.userId(),principal.sessionId());
        if(hashes.isEmpty()||families.isEmpty())throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","다시 로그인해주세요.");
        if(!encoder.matches(currentPassword,hashes.get(0)))throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_CURRENT_PASSWORD","현재 비밀번호가 올바르지 않습니다.");
        if(currentPassword.equals(newPassword))throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"PASSWORD_UNCHANGED","새 비밀번호가 현재 비밀번호와 같습니다.");
        repository.updatePassword(principal.userId(),encoder.encode(newPassword));
        repository.revokeOtherSessions(principal.userId(),families.get(0));
    }
}
