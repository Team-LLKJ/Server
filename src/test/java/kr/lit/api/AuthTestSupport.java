package kr.lit.api;

import com.fasterxml.jackson.databind.*;
import kr.lit.auth.*;
import static kr.lit.auth.AuthModels.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:authenticated;DB_CLOSE_DELAY=-1","lit.ai.provider=stub","lit.grading.results-after-end=false","lit.evaluation.expiry-check-ms=3600000","lit.cors.allowed-origins=https://frontend.example.test","lit.evaluation.end-grace-seconds=0","lit.grading.immediate-submission-analysis=false","lit.grading.job-interval-ms=3600000","lit.grading.process-interval-ms=3600000","lit.archive.interval-ms=3600000","lit.events.interval-ms=3600000"})
@AutoConfigureMockMvc
abstract class AuthTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired EvaluationService evaluations;
    @Autowired WorkService work;
    Tokens teacher;
    String remote;
    static final String PASSWORD="Test-password-123";
    static String unique() {return UUID.randomUUID().toString().replace("-","").substring(0,18);}
    @BeforeEach void accounts() {remote="test-"+unique();teacher=newTeacher();as(teacher);}
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    Tokens newTeacher() {
        String suffix=unique(),email=suffix+"@example.test";
        String token=createVerifiedEmail(email);
        var u=auth.signupTeacher(new TeacherSignup("t_"+suffix,PASSWORD,email,"교사",token,"v1",true,"v1",true));
        return auth.login(new Login(u.username(),PASSWORD));
    }
    // 인증 API 자체는 SignupEmailApiTest에서 메일 대역으로 검증합니다.
    String createVerifiedEmail(String email) {
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO signup_email_verifications(email,verified_at,token_hash,token_expires_at) VALUES (?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP + INTERVAL '15' MINUTE)",email,AuthService.hash(token));
        return token;
    }
    void as(Tokens tokens) {var p=auth.authenticate(tokens.accessToken());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,List.of()));}
    MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder r,Tokens tokens) {
        r.with(req->{req.setRemoteAddr(remote);return req;});return tokens==null?r:r.header("Authorization","Bearer "+tokens.accessToken());
    }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r,Object value) throws Exception {return r.contentType("application/json").content(json.writeValueAsString(value));}
    JsonNode ok(MockHttpServletRequestBuilder r) throws Exception {return json.readTree(mvc.perform(r).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
    @Autowired AutomaticDisclosureService automaticDisclosureForTests;
    /** 제출한 학생 토큰으로는 제출본을 볼 수 없으므로(2026-11-14) 테스트는 저장된 제출본을 직접 읽습니다. */
    String participantOf(String id,String token){return db.queryForObject("SELECT id FROM participants WHERE evaluation_id=? AND ((token_hashed=TRUE AND token=?) OR (token_hashed=FALSE AND token=?))",String.class,id,AuthService.hash(token),token);}
    WorkModels.Submission submissionByToken(String id,String token){
        return db.query("SELECT * FROM submissions WHERE participant_id=?",(r,n)->{try{return new WorkModels.Submission("SUBMITTED",r.getObject("submitted_at",java.time.OffsetDateTime.class).toInstant(),json.readTree(r.getString("snapshot")),r.getString("submission_mode"));}catch(Exception e){throw new IllegalStateException(e);}},participantOf(id,token)).get(0);
    }
    WorkModels.DisclosureView disclosureByToken(String id,String token){return automaticDisclosureForTests.getDisclosure(id,participantOf(id,token));}
}
