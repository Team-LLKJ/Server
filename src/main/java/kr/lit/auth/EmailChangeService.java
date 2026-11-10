package kr.lit.auth;
import kr.lit.api.ApiError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static kr.lit.auth.AuthModels.*;
import static kr.lit.auth.EmailChangeModels.*;
@Service
@Transactional
public class EmailChangeService {
    private static final int MAX_ATTEMPTS=5;
    private final EmailChangeRepository repository;
    private final EmailChangeDeliveryService delivery;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final SecureRandom random=new SecureRandom();
    public EmailChangeService(EmailChangeRepository repository,EmailChangeDeliveryService delivery,PasswordEncoder encoder,AuthService auth) {this.repository=repository;this.delivery=delivery;this.encoder=encoder;this.auth=auth;}
    private ApiError.Failure fail(HttpStatus status,String code,String message) {return new ApiError.Failure(status,code,message);}
    private Map<String,Object> requireAccount(Principal principal,String password) {
        var rows=repository.lockAccount(principal.userId());
        if(rows.isEmpty()||!repository.existsSession(principal.userId(),principal.sessionId()))throw fail(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","다시 로그인해주세요.");
        if(!encoder.matches(password,(String)rows.get(0).get("PASSWORD_HASH")))throw fail(HttpStatus.BAD_REQUEST,"INVALID_CURRENT_PASSWORD","현재 비밀번호가 올바르지 않습니다.");
        return rows.get(0);
    }
    public EmailChangeResponse sendVerification(Principal principal,EmailChangeRequest request) {
        var account=requireAccount(principal,request.currentPassword());
        String email=request.email().strip().toLowerCase(Locale.ROOT);
        if(email.equals(account.get("EMAIL")))throw fail(HttpStatus.BAD_REQUEST,"EMAIL_UNCHANGED","현재 이메일과 같습니다.");
        if(repository.existsEmail(email))throw fail(HttpStatus.CONFLICT,"EMAIL_ALREADY_EXISTS","이미 사용 중인 이메일입니다.");
        var now=OffsetDateTime.now(ZoneOffset.UTC);var rows=repository.getChallenge(principal.userId());
        if(!rows.isEmpty()&&((OffsetDateTime)rows.get(0).get("LAST_SENT_AT")).plusSeconds(60).isAfter(now))throw fail(HttpStatus.TOO_MANY_REQUESTS,"EMAIL_RESEND_LIMITED","인증메일은 60초 후 다시 요청해주세요.");
        String code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
        delivery.sendVerificationCode(email,code);
        repository.saveChallenge(principal.userId(),email,encoder.encode(code),(String)account.get("PASSWORD_HASH"),now);
        return new EmailChangeResponse(now.plusMinutes(10).toInstant(),60);
    }
    @Transactional(noRollbackFor=ApiError.Failure.class)
    public User confirmEmail(Principal principal,EmailConfirmRequest request) {
        var account=requireAccount(principal,request.currentPassword());var now=OffsetDateTime.now(ZoneOffset.UTC);
        String email=request.email().strip().toLowerCase(Locale.ROOT);var rows=repository.getChallenge(principal.userId());
        if(rows.isEmpty())throw fail(HttpStatus.BAD_REQUEST,"EMAIL_CODE_EXPIRED","인증번호를 다시 발송해주세요.");
        var challenge=rows.get(0);
        if(!((OffsetDateTime)challenge.get("EXPIRES_AT")).isAfter(now)||!account.get("PASSWORD_HASH").equals(challenge.get("PASSWORD_HASH")))throw fail(HttpStatus.BAD_REQUEST,"EMAIL_CODE_EXPIRED","인증번호를 다시 발송해주세요.");
        if(((Number)challenge.get("ATTEMPTS")).intValue()>=MAX_ATTEMPTS)throw fail(HttpStatus.TOO_MANY_REQUESTS,"EMAIL_ATTEMPTS_EXCEEDED","인증번호를 다시 발송해주세요.");
        if(!email.equals(challenge.get("EMAIL"))||!encoder.matches(request.code(),(String)challenge.get("CODE_HASH"))) {
            repository.incrementAttempts(principal.userId());throw fail(HttpStatus.BAD_REQUEST,"INVALID_EMAIL_CODE","인증번호 또는 이메일이 올바르지 않습니다.");
        }
        try {repository.updateEmail(principal.userId(),email,now);}
        catch(DuplicateKeyException ex) {throw fail(HttpStatus.CONFLICT,"EMAIL_ALREADY_EXISTS","이미 사용 중인 이메일입니다.");}
        repository.deleteChallenge(principal.userId());
        repository.invalidateSignupVerification(email);
        repository.invalidateSignupVerification((String)account.get("EMAIL"));
        return auth.me(principal.userId());
    }
}
