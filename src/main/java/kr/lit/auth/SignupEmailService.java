package kr.lit.auth;
import kr.lit.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static kr.lit.auth.SignupEmailModels.*;
@Service
@Transactional
public class SignupEmailService {
    private static final int MAX_ATTEMPTS=5;
    private final SignupEmailRepository repository;
    private final SignupEmailDeliveryService delivery;
    private final PasswordEncoder encoder;
    private final SecureRandom random=new SecureRandom();
    public SignupEmailService(SignupEmailRepository repository,SignupEmailDeliveryService delivery,PasswordEncoder encoder) {this.repository=repository;this.delivery=delivery;this.encoder=encoder;}
    private static String normalizeEmail(String email) {return email.strip().toLowerCase(Locale.ROOT);}
    private static ApiError.Failure failure(HttpStatus status,String code,String message) {return new ApiError.Failure(status,code,message);}
    private static OffsetDateTime getNow() {return OffsetDateTime.now(ZoneOffset.UTC);}
    public EmailVerificationResponse sendVerification(String rawEmail) {
        String email=normalizeEmail(rawEmail);
        if(repository.existsEmail(email))throw failure(HttpStatus.CONFLICT,"EMAIL_ALREADY_EXISTS","이미 가입한 이메일입니다.");
        var row=repository.lockVerification(email);var now=getNow();var last=(OffsetDateTime)row.get("LAST_SENT_AT");
        if(last!=null&&last.plusSeconds(60).isAfter(now))throw failure(HttpStatus.TOO_MANY_REQUESTS,"EMAIL_RESEND_LIMITED","인증메일은 60초 후 다시 요청해주세요.");
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
        delivery.sendVerificationCode(email,code);
        var expires=now.plusMinutes(10);repository.saveChallenge(email,encoder.encode(code),now,expires);
        return new EmailVerificationResponse(expires.toInstant(),60);
    }
    @Transactional(noRollbackFor=ApiError.Failure.class)
    public EmailConfirmationResponse confirmEmail(String rawEmail,String code) {
        String email=normalizeEmail(rawEmail);var row=repository.lockVerification(email);var now=getNow();
        var expiry=(OffsetDateTime)row.get("CODE_EXPIRES_AT");String hash=(String)row.get("CODE_HASH");
        if(hash==null||expiry==null||!expiry.isAfter(now))throw failure(HttpStatus.BAD_REQUEST,"EMAIL_CODE_EXPIRED","인증번호를 다시 발송해주세요.");
        if(((Number)row.get("ATTEMPTS")).intValue()>=MAX_ATTEMPTS)throw failure(HttpStatus.TOO_MANY_REQUESTS,"EMAIL_ATTEMPTS_EXCEEDED","인증 시도 횟수를 초과했습니다. 인증번호를 다시 발송해주세요.");
        if(!encoder.matches(code,hash)) {repository.incrementAttempts(email);throw failure(HttpStatus.BAD_REQUEST,"INVALID_EMAIL_CODE","인증번호가 올바르지 않습니다.");}
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var tokenExpiry=now.plusMinutes(15);repository.saveConfirmation(email,AuthService.hash(token),now,tokenExpiry);
        return new EmailConfirmationResponse(token,tokenExpiry.toInstant());
    }
    /** 가입 트랜잭션에 참여하므로 가입 실패 시 인증 토큰 소비도 롤백됩니다. */
    public void consumeVerification(String rawEmail,String token) {
        String email=normalizeEmail(rawEmail);var row=repository.lockVerification(email);var now=getNow();
        var expires=(OffsetDateTime)row.get("TOKEN_EXPIRES_AT");
        if(token==null||row.get("VERIFIED_AT")==null||row.get("USED_AT")!=null||expires==null||!expires.isAfter(now)
                ||!AuthService.hash(token).equals(row.get("TOKEN_HASH")))
            throw failure(HttpStatus.BAD_REQUEST,"EMAIL_VERIFICATION_REQUIRED","유효한 이메일 인증을 완료해주세요.");
        repository.consumeToken(email,now);
    }
    @Transactional(readOnly=true)
    public UsernameAvailabilityResponse getUsernameAvailability(String username) {
        if(username==null||!username.matches("[A-Za-z0-9_]{4,30}"))throw failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","아이디는 영문·숫자·밑줄 4~30자로 입력해주세요.");
        String normalized=username.toLowerCase(Locale.ROOT);
        return new UsernameAvailabilityResponse(normalized,!repository.existsUsername(normalized));
    }
}
