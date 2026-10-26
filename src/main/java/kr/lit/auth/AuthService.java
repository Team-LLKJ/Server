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
    private final ResetMailer mail;
    private final String dummyHash;
    private final SecureRandom random=new SecureRandom();
    public AuthService(JdbcTemplate db,PasswordEncoder encoder,ResetMailer mail) {
        this.db=db;this.encoder=encoder;this.mail=mail;this.dummyHash=encoder.encode("non-user-timing-comparison-only");
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
        var invites=db.queryForList("SELECT * FROM teacher_invites WHERE code_hash=? FOR UPDATE",hash(s.inviteCode()));
        if(invites.isEmpty()) throw fail(HttpStatus.BAD_REQUEST,"INVALID_INVITE","유효하지 않은 교사 초대 코드입니다.");
        var invite=invites.get(0);
        if(invite.get("USED_BY")!=null||!((OffsetDateTime)invite.get("EXPIRES_AT")).isAfter(now())||!s.school().strip().equals(invite.get("SCHOOL")))
            throw fail(HttpStatus.BAD_REQUEST,"INVALID_INVITE","만료·사용된 코드이거나 학교가 일치하지 않습니다.");
        var user=signup(s.username(),s.password(),s.email(),s.name(),s.school(),Role.TEACHER,null,null,null);
        db.update("UPDATE teacher_invites SET used_by=? WHERE code_hash=?",user.id(),hash(s.inviteCode()));return user;
    }
    public Invitation invite(int hours) {
        var actor=AccountAccess.require(Role.TEACHER);var u=me(actor.userId());
        String code=secret();var expires=now().plusHours(hours);
        db.update("INSERT INTO teacher_invites VALUES (?,?,?,?,NULL,?)",hash(code),u.school(),u.id(),expires,now());
        return new Invitation(code,u.school(),expires.toInstant());
    }
    private Tokens issue(String userId,String familyId,OffsetDateTime familyExpiry) {
        String access=secret(),refresh=secret(),id=UUID.randomUUID().toString();var timestamp=now();
        var accessExpiry=timestamp.plusMinutes(15);if(accessExpiry.isAfter(familyExpiry))accessExpiry=familyExpiry;
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
    @Transactional(noRollbackFor=ApiError.Failure.class)
    public Tokens refresh(String token) {
        // Lock the user before the session so reset/logout/refresh have consistent lock ordering.
        var ids=db.queryForList("SELECT user_id FROM auth_sessions WHERE refresh_hash=?",String.class,hash(token));
        if(ids.isEmpty())throw fail(HttpStatus.UNAUTHORIZED,"INVALID_REFRESH_TOKEN","유효하지 않은 갱신 토큰입니다.");
        String uid=ids.get(0);db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",uid);
        var row=db.queryForList("SELECT * FROM auth_sessions WHERE refresh_hash=? FOR UPDATE",hash(token)).get(0);
        String family=(String)row.get("FAMILY_ID");
        if(row.get("ROTATED_AT")!=null) {
            db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE family_id=?",now(),family);
            throw fail(HttpStatus.UNAUTHORIZED,"REFRESH_TOKEN_REUSED","이미 사용된 갱신 토큰입니다. 다시 로그인해주세요.");
        }
        if(row.get("REVOKED_AT")!=null||!((OffsetDateTime)row.get("REFRESH_EXPIRES_AT")).isAfter(now())||(!me(uid).status().equals("ACTIVE")||me(uid).role()!=Role.TEACHER))
            throw fail(HttpStatus.UNAUTHORIZED,"INVALID_REFRESH_TOKEN","만료되었거나 유효하지 않은 갱신 토큰입니다.");
        db.update("UPDATE auth_sessions SET revoked_at=?,rotated_at=? WHERE id=?",now(),now(),row.get("ID"));
        return issue(uid,family,(OffsetDateTime)row.get("REFRESH_EXPIRES_AT"));
    }
    public void logout(Principal p) {
        db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",p.userId());
        db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE family_id=(SELECT family_id FROM auth_sessions WHERE id=? AND user_id=?)",now(),p.sessionId(),p.userId());
    }
    public void requestReset(String email) {
        mail.checkConfigured();
        var ids=db.queryForList("SELECT id FROM app_users WHERE email=? AND user_status='ACTIVE' AND user_role='TEACHER'",String.class,normalized(email));
        if(ids.isEmpty())return;
        String id=ids.get(0);db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",id);
        int recent=db.queryForObject("SELECT COUNT(*) FROM password_resets WHERE user_id=? AND created_at>?",Integer.class,id,now().minusMinutes(1));
        if(recent>0)return;
        String token=secret();db.update("INSERT INTO password_resets VALUES (?,?,?,NULL,?)",hash(token),id,now().plusMinutes(30),now());
        mail.send(normalized(email),token);
    }
    public void confirmReset(ResetConfirm input) {
        String digest=hash(input.token());var ids=db.queryForList("SELECT user_id FROM password_resets WHERE token_hash=?",String.class,digest);
        if(ids.isEmpty())throw fail(HttpStatus.BAD_REQUEST,"INVALID_RESET_TOKEN","만료되었거나 사용된 재설정 링크입니다.");
        String id=ids.get(0);db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",id);
        var row=db.queryForList("SELECT * FROM password_resets WHERE token_hash=? FOR UPDATE",digest).get(0);
        if(me(id).role()!=Role.TEACHER||row.get("USED_AT")!=null||!((OffsetDateTime)row.get("EXPIRES_AT")).isAfter(now()))throw fail(HttpStatus.BAD_REQUEST,"INVALID_RESET_TOKEN","만료되었거나 사용된 재설정 링크입니다.");
        db.update("UPDATE app_users SET password_hash=?,failed_logins=0,locked_until=NULL,updated_at=? WHERE id=?",encoder.encode(input.newPassword()),now(),id);
        db.update("UPDATE password_resets SET used_at=? WHERE user_id=? AND used_at IS NULL",now(),id);
        db.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE user_id=?",now(),id);
    }
    public void bootstrap(String code,String school) {
        if(code.isBlank())return;
        if(code.length()<32||code.length()>128||school.isBlank()||school.length()>100)throw new IllegalStateException("Bootstrap invitation requires a 32..128 character code and a school name");
        if(db.queryForObject("SELECT COUNT(*) FROM teacher_invites WHERE code_hash=?",Integer.class,hash(code))==0)
            db.update("INSERT INTO teacher_invites VALUES (?,?,NULL,?,NULL,?)",hash(code),school.strip(),now().plusHours(24),now());
    }
}
