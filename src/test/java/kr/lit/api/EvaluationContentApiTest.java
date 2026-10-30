package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class EvaluationContentApiTest extends AuthTestSupport {
    @Test void migratesExistingDatabaseWithoutDeletingEvaluation() {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:content_migration;DB_CLOSE_DELAY=-1","sa","");
        var old=new org.springframework.jdbc.core.JdbcTemplate(source);
        old.execute("CREATE TABLE evaluations (id VARCHAR(36) PRIMARY KEY,title VARCHAR(100) NOT NULL,pin VARCHAR(6) NOT NULL UNIQUE,state VARCHAR(20) NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        old.update("INSERT INTO evaluations VALUES ('legacy','기존 평가','000123','WAITING',CURRENT_TIMESTAMP)");
        var schema=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql"));
        schema.execute(source);schema.execute(source);
        assertThat(old.queryForObject("SELECT evaluation_content FROM evaluations WHERE id='legacy'",String.class)).isEmpty();
        assertThat(old.queryForObject("SELECT title FROM evaluations WHERE id='legacy'",String.class)).isEqualTo("기존 평가");
    }
    @Test void savesOnCreationAndSharesInStudentWorkspace() throws Exception {
        String text="평가 기준\n근거를 제시하세요. AI 사용 규칙을 지켜주세요.";
        var created=ok(body(request(post("/api/teacher/evaluations"),teacher),Map.of(
                "title","내용 공유","expiresInMinutes",60,"evaluationContent",text)));
        String id=created.get("id").asText();
        assertThat(db.queryForObject("SELECT evaluation_content FROM evaluations WHERE id=?",String.class,id)).isEqualTo(text);
        mvc.perform(request(get("/api/teacher/evaluations/"+id+"/content"),teacher))
                .andExpect(status().isOk()).andExpect(jsonPath("evaluationContent").value(text));
        var joined=ok(body(post("/api/participation/join"),Map.of("pin",created.get("pin").asText(),"nickname","참가 학생")));
        String token=joined.get("participantToken").asText();
        mvc.perform(get("/api/evaluations/"+id+"/content").header("X-Participant-Token",token))
                .andExpect(status().isOk()).andExpect(jsonPath("evaluationContent").value(text));
        as(teacher);evaluations.start(id);
        mvc.perform(get("/api/evaluations/"+id+"/questions").header("X-Participant-Token",token))
                .andExpect(status().isOk()).andExpect(jsonPath("evaluationContent").value(text));
        mvc.perform(body(request(put("/api/teacher/evaluations/"+id+"/content"),teacher),Map.of("evaluationContent","변경")))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("EVALUATION_CONTENT_LOCKED"));
    }
    @Test void supportsOldClientsEditingAndClearingWithoutLosingWhitespace() throws Exception {
        var e=evaluations.create("기존 요청",60);String path="/api/teacher/evaluations/"+e.id()+"/content";
        mvc.perform(request(get(path),teacher)).andExpect(status().isOk()).andExpect(jsonPath("evaluationContent").value(""));
        for(String text:new String[]{"  한글 기준\n둘째 줄  ",""}) {
            mvc.perform(body(request(put(path),teacher),Map.of("evaluationContent",text)))
                    .andExpect(status().isOk()).andExpect(jsonPath("evaluationContent").value(text));
            mvc.perform(request(get(path),teacher)).andExpect(jsonPath("evaluationContent").value(text));
        }
    }
    @Test void enforcesTeacherOwnershipAndParticipantScope() throws Exception {
        var e=evaluations.create("비공개",60);var joined=evaluations.join(e.pin(),"학생",null);
        var other=newTeacher();String path="/api/teacher/evaluations/"+e.id()+"/content";
        mvc.perform(request(get(path),other)).andExpect(status().isNotFound());
        mvc.perform(body(request(put(path),other),Map.of("evaluationContent","탈취"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/evaluations/"+e.id()+"/content")).andExpect(status().isUnauthorized());
        as(teacher);var second=evaluations.create("다른 방",60);
        mvc.perform(get("/api/evaluations/"+second.id()+"/content").header("X-Participant-Token",joined.participantToken()))
                .andExpect(status().isUnauthorized());
    }
    @Test void rejectsInvalidInputAndExpiredOrEndedChanges() throws Exception {
        var e=evaluations.create("검증",60);String path="/api/teacher/evaluations/"+e.id()+"/content";
        mvc.perform(body(request(put(path),teacher),Map.of())).andExpect(status().isBadRequest());
        mvc.perform(body(request(put(path),teacher),Map.of("evaluationContent","가".repeat(10001)))).andExpect(status().isBadRequest());
        mvc.perform(body(request(post("/api/teacher/evaluations"),teacher),Map.of("title","검증","expiresInMinutes",60,"evaluationContent","가".repeat(10001))))
                .andExpect(status().isBadRequest());
        db.update("UPDATE evaluations SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",e.id());
        mvc.perform(body(request(put(path),teacher),Map.of("evaluationContent","변경"))).andExpect(status().isGone());
        as(teacher);evaluations.end(e.id());
        mvc.perform(body(request(put(path),teacher),Map.of("evaluationContent","변경"))).andExpect(status().isConflict());
    }
}
