package kr.lit.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class GuidelinesHtmlApiTest extends AuthTestSupport {
    @Test void guidelinesRetainSafeFormattingAcrossCreateConfigureContentAndStudentWorkspace() throws Exception {
        String html="<h2>수행평가</h2><p><strong>굵게</strong><u>밑줄</u></p><ol><li>근거 제시</li></ol><script>bad()</script><p onclick='bad()'>본문</p>";
        var created=ok(body(request(post("/api/teacher/evaluations"),teacher),Map.of("title","HTML 안내","expiresInMinutes",60,"guidelines",html)));
        String id=created.path("id").asText();as(teacher);
        String stored=db.queryForObject("SELECT evaluation_content FROM evaluations WHERE id=?",String.class,id);
        assertThat(stored).contains("<h2>","<strong>","<u>","<ol>","<li>").doesNotContain("script","onclick","bad()");
        mvc.perform(body(request(put("/api/teacher/evaluations/"+id+"/content"),teacher),Map.of("evaluationContent","<ul><li><b>수정 안내</b></li></ul>")))
                .andExpect(status().isOk()).andExpect(jsonPath("evaluationContent").value("<ul><li><b>수정 안내</b></li></ul>"));
        mvc.perform(request(get("/api/teacher/evaluations/"+id+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("guidelines").value("<ul><li><b>수정 안내</b></li></ul>"));
        var config=Map.of("aiPolicy","FULL","guidelines","<p><em>학생 안내</em></p>","questions",List.of(Map.of("position",1,"title","문항","content","문제")));
        mvc.perform(body(request(put("/api/teacher/evaluations/"+id+"/configuration"),teacher),config)).andExpect(status().isOk());
        as(teacher);var e=evaluations.details(id);var p=evaluations.join(e.pin(),"학생",null);evaluations.start(id);
        mvc.perform(get("/api/student/evaluations/"+id+"/workspace").header("X-Participant-Token",p.participantToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("guidelines").value("<p><em>학생 안내</em></p>")).andExpect(jsonPath("evaluationContent").value("<p><em>학생 안내</em></p>"));
    }
    @Test void normalizedOversizedGuidelinesAreRejectedWithoutTruncatingOldContent() throws Exception {
        var e=evaluations.create("길이 검증",60,"기존 내용");
        mvc.perform(body(request(put("/api/teacher/evaluations/"+e.id()+"/content"),teacher),Map.of("evaluationContent","<p>"+"&".repeat(6000)+"</p>")))
                .andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT evaluation_content FROM evaluations WHERE id=?",String.class,e.id())).isEqualTo("기존 내용");
    }
}
