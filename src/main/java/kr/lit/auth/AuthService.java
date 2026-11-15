package kr.lit.auth;

import kr.lit.api.ApiError;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import static kr.lit.auth.AuthModels.*;

@Service
@Transactional
public class AuthService {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final SignupEmailService signupEmail;
    private final PasswordChangeService passwordChangeService;
    private final NicknameService nicknameService;
    private final kr.lit.api.ConsentService consents;
    private final String dummyHash;
    private final SecureRandom random=new SecureRandom();
    public AuthService(JdbcTemplate db,PasswordEncoder encoder,SignupEmailService signupEmail,PasswordChangeService passwordChangeService,NicknameService nicknameService,kr.lit.api.ConsentService consents) {
        this.passwordChangeService=passwordChangeService;
        this.nicknameService=nicknameService;this.consents=consents;
        this.db=db;this.encoder=encoder;this.signupEmail=signupEmail;this.dummyHash=encoder.encode("non-user-timing-comparison-only");
    }
    public static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private String secret() {byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private OffsetDateTime now() {return OffsetDateTime.now(ZoneOffset.UTC);}
    private ApiError.Failure fail(HttpStatus status,String code,String message) {return new ApiError.Failure(status,code,message);}
    private String normalized(String value) {return value.strip().toLowerCase(Locale.ROOT);}
    private User user(ResultSet r,int n) throws SQLException {
        return new User(r.getString("id"),r.getString("username"),r.getString("email"),r.getString("display_name"),r.getString("school"),Role.valueOf(r.getString("user_role")),r.getString("user_status"),
                r.getObject("grade",Integer.class),r.getObject("class_number",Integer.class),r.getObject("student_number",Integer.class),r.getObject("created_at",OffsetDateTime.class).toInstant());
    }
    public User me(String id) {return db.query("SELECT * FROM app_users WHERE id=?",this::user,id).stream().findFirst().orElseThrow(()->fail(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","로그인이 필요합니다."));}
    private User signup(String username,String password,String email,String name,String school,Role role,Integer grade,Integer classNo,Integer number) {
        String id=UUID.randomUUID().toString();var timestamp=now();
        try {
            db.update("INSERT INTO app_users(id,username,email,password_hash,display_name,school,user_role,user_status,grade,class_number,student_number,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?)",
                    id,normalized(username),normalized(email),encoder.encode(password),name.strip(),school.strip(),role.name(),grade,classNo,number,timestamp,timestamp);
        } catch(DuplicateKeyException e) {throw fail(HttpStatus.CONFLICT,"ACCOUNT_ALREADY_EXISTS","이미 사용 중인 아이디 또는 이메일입니다.");}
        return me(id);
    }
    public User signupTeacher(TeacherSignup s) {
        var request=new kr.lit.api.ConsentModels.ConsentRequest(s.termsVersion(),s.termsAccepted(),s.privacyVersion(),s.privacyAccepted());
        consents.validateConsent(request);
        signupEmail.consumeVerification(s.email(),s.emailVerificationToken());
        var user=signup(s.username(),s.password(),s.email(),s.name(),"",Role.TEACHER,null,null,null);
        consents.recordSignupConsents(user.id(),request);return user;
    }
    private Tokens issue(String userId,String familyId,OffsetDateTime familyExpiry) {
        return issue(userId,familyId,familyExpiry,UUID.randomUUID().toString(),secret(),secret());
    }
    private Tokens issue(String userId,String familyId,OffsetDateTime familyExpiry,String id,String access,String refresh) {
        // Match database timestamp precision so the original and repeated refresh return the same expiry.
        var timestamp=now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var accessExpiry=timestamp.plusHours(2);if(accessExpiry.isAfter(familyExpiry))accessExpiry=familyExpiry;
        db.update("INSERT INTO auth_sessions VALUES (?,?,?,?,?,?,?,NULL,NULL,?)",id,userId,familyId,hash(access),hash(refresh),accessExpiry,familyExpiry,timestamp);
        return new Tokens("Bearer",access,accessExpiry.toInstant(),refresh,familyExpiry.toInstant(),me(userId));
    }
    @Transactional(noRollbackFor=ApiError.Failure.class)
    public Tokens login(Login input) {
        var rows=db.queryForList("SELECT * FROM app_users WHERE username=? FOR UPDATE",normalized(input.username()));
        if(rows.isEmpty()) {encoder.matches(input.password(),dummyHash);throw fail(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","아이디 또는 비밀번호가 올바르지 않습니다.");}
        var row=rows.get(0);String id=(String)row.get("ID");
        var locked=(OffsetDateTime)row.get("LOCKED_UNTIL");
        if(locked!=null&&locked.isAfter(now())) {encoder.matches(input.password(),dummyHash);throw fail(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","아이디 또는 비밀번호가 올바르지 않습니다.");}
        boolean valid=encoder.matches(input.password(),(String)row.get("PASSWORD_HASH"));
        if(!valid||(!"ACTIVE".equals(row.get("USER_STATUS"))||!"TEACHER".equals(row.get("USER_ROLE")))) {
            int failures=locked!=null?1:((Number)row.get("FAILED_LOGINS")).intValue()+1;
            db.update("UPDATE app_users SET failed_logins=?,locked_until=?,updated_at=? WHERE id=?",failures,failures>=5?now().plusMinutes(15):null,now(),id);
            throw fail(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        db.update("UPDATE app_users SET failed_logins=0,locked_until=NULL,updated_at=? WHERE id=?",now(),id);
        return issue(id,UUID.randomUUID().toString(),now().plusDays(14));
    }
    public Principal authenticate(String token) {
        var rows=db.query("SELECT s.id,s.user_id,u.user_role FROM auth_sessions s JOIN app_users u ON s.user_id=u.id WHERE s.access_hash=? AND s.revoked_at IS NULL AND s.access_expires_at>CURRENT_TIMESTAMP AND u.user_status='ACTIVE' AND u.user_role='TEACHER'",
                (r,n)->new Principal(r.getString("user_id"),Role.valueOf(r.getString("user_role")),r.getString("id")),hash(token));
        if(rows.isEmpty())throw fail(HttpStatus.UNAUTHORIZED,"INVALID_ACCESS_TOKEN","만료되었거나 유효하지 않은 로그인 토큰입니다.");return rows.get(0);
    }
    public Tokens refresh(String token) {
        // Lock the user before the session so logout and refresh have consistent lock ordering.
        var ids=db.queryForList("SELECT user_id FROM auth_sessions WHERE refresh_hash=?",String.class,hash(token));
        if(ids.isEmpty())throw fail(HttpStatus.UNAUTHORIZED,"INVALID_REFRESH_TOKEN","유효하지 않은 갱신 토큰입니다.");
        String uid=ids.get(0);db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",uid);
        var rows=db.queryForList("SELECT * FROM auth_sessions WHERE refresh_hash=? FOR UPDATE",hash(token));
        if(rows.isEmpty())throw invalidRefresh();
        var row=rows.get(0);
        String family=(String)row.get("FAMILY_ID");
        var expiry=(OffsetDateTime)row.get("REFRESH_EXPIRES_AT");
        var timestamp=now();
        var user=me(uid);
        if(!expiry.isAfter(timestamp)||!"ACTIVE".equals(user.status())||user.role()!=Role.TEACHER)throw invalidRefresh();
        if(row.get("ROTATED_AT") instanceof OffsetDateTime rotatedAt) {
            if(rotatedAt.plusSeconds(10).isAfter(timestamp)) {
                var successors=db.queryForList("SELECT id,refresh_hash,access_expires_at FROM auth_sessions WHERE family_id=? AND revoked_at IS NULL AND rotated_at IS NULL",family);
                for(var successor:successors) {
                    String id=(String)successor.get("ID");
                    String refresh=rotationToken(token,id,"refresh");
                    if(hash(refresh).equals(successor.get("REFRESH_HASH"))) {
                        return new Tokens("Bearer",rotationToken(token,id,"access"),
                                ((OffsetDateTime)successor.get("ACCESS_EXPIRES_AT")).toInstant(),refresh,expiry.toInstant(),user);
                    }
                }
            }
            throw fail(HttpStatus.UNAUTHORIZED,"REFRESH_TOKEN_REUSED","이미 사용된 갱신 토큰입니다.");
        }
        if(row.get("REVOKED_AT")!=null)throw invalidRefresh();
        String id=UUID.randomUUID().toString();
        db.update("UPDATE auth_sessions SET revoked_at=?,rotated_at=? WHERE id=?",timestamp,timestamp,row.get("ID"));
        return issue(uid,family,expiry,id,rotationToken(token,id,"access"),rotationToken(token,id,"refresh"));
    }
    private String rotationToken(String previousToken,String sessionId,String purpose) {
        // A fresh internal session ID lets a short retry recover the same tokens without storing plaintext.
        byte[] bytes=HexFormat.of().parseHex(hash(purpose+":"+sessionId+":"+previousToken));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private ApiError.Failure invalidRefresh() {
        return fail(HttpStatus.UNAUTHORIZED,"INVALID_REFRESH_TOKEN","만료되었거나 유효하지 않은 갱신 토큰입니다.");
    }
    public void logout(Principal p) {
        db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",p.userId());
        db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE family_id=(SELECT family_id FROM auth_sessions WHERE id=? AND user_id=?)",now(),p.sessionId(),p.userId());
    }
    public User updateName(Principal p,String name) {
        nicknameService.updateNickname(p,name);
        return me(p.userId());
    }
    /** 현재 비밀번호 확인 후 변경하고, 지금 로그인한 세션을 제외한 다른 기기의 세션을 모두 폐기합니다. */
    public void changePassword(Principal p,String current,String next) {
        passwordChangeService.changePassword(p,current,next);
    }
}
