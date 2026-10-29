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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:authenticated;DB_CLOSE_DELAY=-1","lit.ai.provider=stub","lit.cors.allowed-origins=https://frontend.example.test"})
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
        String suffix=unique(),code=UUID.randomUUID().toString()+UUID.randomUUID();auth.bootstrap(code,"테스트학교");
        var u=auth.signupTeacher(new TeacherSignup("t_"+suffix,PASSWORD,suffix+"@example.test","교사","테스트학교",code));
        return auth.login(new Login(u.username(),PASSWORD));
    }
    void as(Tokens tokens) {var p=auth.authenticate(tokens.accessToken());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,List.of()));}
    MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder r,Tokens tokens) {
        r.with(req->{req.setRemoteAddr(remote);return req;});return tokens==null?r:r.header("Authorization","Bearer "+tokens.accessToken());
    }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r,Object value) throws Exception {return r.contentType("application/json").content(json.writeValueAsString(value));}
    JsonNode ok(MockHttpServletRequestBuilder r) throws Exception {return json.readTree(mvc.perform(r).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
}
