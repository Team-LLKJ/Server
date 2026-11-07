package kr.lit.auth;

import kr.lit.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class PasswordResetService {
    private final PasswordResetRepository repository;
    private final PasswordResetDeliveryService delivery;
    private final PasswordEncoder encoder;
    private final SecureRandom random=new SecureRandom();
    public PasswordResetService(PasswordResetRepository repository,PasswordResetDeliveryService delivery,PasswordEncoder encoder) {
        this.repository=repository;this.delivery=delivery;this.encoder=encoder;
    }
    private ApiError.Failure fail(String code,String message) {return new ApiError.Failure(HttpStatus.BAD_REQUEST,code,message);}
    public void requestPasswordReset(String rawEmail) {
        delivery.requireConfiguration();
        String email=rawEmail.strip().toLowerCase(Locale.ROOT);
        var account=repository.lockAccountByEmail(email);
        if(account.isEmpty())return;
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        // 응답으로 계정 존재나 재발송 제한 여부를 노출하지 않습니다.
        if(repository.hasRecentRequest(account.get().id(),now.minusSeconds(60)))return;
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // SMTP 실패 시 기존 토큰 무효화와 새 토큰 저장은 모두 롤백됩니다.
        repository.invalidateTokens(account.get().id(),now);
        repository.saveToken(AuthService.hash(token),account.get(),now,now.plusMinutes(15));
        delivery.sendResetLink(email,token);
    }
    public void confirmPasswordReset(String token,String newPassword) {
        String hash=AuthService.hash(token);
        String userId=repository.getTokenUserId(hash).orElseThrow(()->fail("PASSWORD_RESET_INVALID","유효하지 않은 재설정 링크입니다. 다시 요청해주세요."));
        // 로그인·갱신과 같은 계정 → 토큰/세션 순서로 잠가 동시 재설정을 직렬화합니다.
        var account=repository.lockAccountById(userId).orElseThrow(()->fail("PASSWORD_RESET_INVALID","유효하지 않은 재설정 링크입니다. 다시 요청해주세요."));
        var reset=repository.lockToken(hash).orElseThrow(()->fail("PASSWORD_RESET_INVALID","유효하지 않은 재설정 링크입니다. 다시 요청해주세요."));
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        if(reset.usedAt()!=null)throw fail("PASSWORD_RESET_USED","이미 사용된 링크입니다. 다시 요청해주세요.");
        if(reset.invalidatedAt()!=null||!reset.passwordFingerprint().equals(AuthService.hash(account.passwordHash()+"\n"+account.email())))
            throw fail("PASSWORD_RESET_INVALID","새 링크 발급 또는 비밀번호 변경으로 무효화된 링크입니다.");
        if(!reset.expiresAt().isAfter(now))throw fail("PASSWORD_RESET_EXPIRED","만료된 링크입니다. 다시 요청해주세요.");
        if(encoder.matches(newPassword,account.passwordHash()))throw fail("PASSWORD_UNCHANGED","현재 비밀번호와 다른 비밀번호를 입력해주세요.");
        repository.updatePassword(userId,encoder.encode(newPassword),now);
        repository.consumeToken(hash,now);
        repository.invalidateTokens(userId,now);
    }
}
