package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrontendRequestedApiTest extends AuthTestSupport {
    Map<String,Object> templateInput() {
        var map=new LinkedHashMap<String,Object>();map.put("title","프런트 계약");map.put("guidelines","<p>공통 안내</p>");map.put("aiPolicy","HINT_ONLY");
        map.put("criteria",List.of("근거의 적절성","자기 생각"));map.put("antiCheat",List.of("TAB_LEAVE","PRINT_BLOCK"));
        map.put("questions",List.of(Map.of("position",1,"title","문항","content","문제","minChars",1,"maxChars",99999)));return map;
    }
    JsonNode createTemplate() throws Exception {return ok(body(request(post("/api/teacher/assessments"),teacher),templateInput()));}
    JsonNode add(String id) throws Exception {return ok(body(request(post("/api/teacher/assessments/"+id+"/classes"),teacher),Map.of("className","1반")));}
    JsonNode start(String id,String c) throws Exception {return ok(body(request(post("/api/teacher/assessments/"+id+"/classes/"+c+"/start"),teacher),Map.of("expiresInMinutes",40)));}
    @Test void frontendTitleAndAntiCheatArraysRoundTripAndReadyStateIsExplicit() throws Exception {
        var t=createTemplate();assertThat(t.path("criteria").get(0).asText()).isEqualTo("근거의 적절성");assertThat(t.path("antiCheat").get(1).asText()).isEqualTo("PRINT_BLOCK");
        String id=t.path("id").asText();var c=add(id);assertThat(c.path("state").asText()).isEqualTo("READY");assertThat(c.path("evaluationState").asText()).isEqualTo("WAITING");
        var s=start(id,c.path("classId").asText());as(teacher);var p=evaluations.join(s.path("pin").asText(),"학생",null);
        mvc.perform(get("/api/student/evaluations/"+c.path("classId").asText()+"/workspace").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk())
                .andExpect(jsonPath("criteria[0]").value("근거의 적절성")).andExpect(jsonPath("antiCheat[0]").value("TAB_LEAVE"));
        // 제목 선택만으로 임의의100점 배점을 생성하지 않습니다.
        assertThat(db.queryForObject("SELECT COUNT(*) FROM evaluation_rubrics WHERE evaluation_id=?",Integer.class,c.path("classId").asText())).isZero();
    }
    @Test void existingConfigurationAcceptsFrontendArraysToo() throws Exception {
        var e=evaluations.create("기존 설정",60);var v=templateInput();v.remove("title");v.remove("guidelines");
        mvc.perform(body(request(put("/api/teacher/evaluations/"+e.id()+"/configuration"),teacher),v)).andExpect(status().isOk()).andExpect(jsonPath("criteria[1]").value("자기 생각"));
        mvc.perform(request(get("/api/teacher/evaluations/"+e.id()+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("antiCheat[1]").value("PRINT_BLOCK"));
    }
    @Test void selectionArraysRejectBlankDuplicateMixedAndUnsupportedCodes() throws Exception {
        for(var criteria:List.of(List.of(""),List.of("중복","중복"),List.of("제목",Map.of("name","상세","maxScore",100)))) {
            var v=templateInput();v.put("criteria",criteria);
            mvc.perform(body(request(post("/api/teacher/assessments"),teacher),v)).andExpect(status().isBadRequest());
        }
        for(var anti:List.of(List.of("TAB_LEAVE","TAB_LEAVE"),List.of("UNKNOWN"))) {
            var v=templateInput();v.put("antiCheat",anti);
            mvc.perform(body(request(post("/api/teacher/assessments"),teacher),v)).andExpect(status().isBadRequest());
        }
    }
    @Test void explicitCharacterLimitsMustBeOneTo99999ButMayBeOmitted() throws Exception {
        for(int limit:List.of(0,100000,-1)) {
            var v=templateInput();v.put("questions",List.of(Map.of("position",1,"title","q","content","c","minChars",limit)));
            mvc.perform(body(request(post("/api/teacher/assessments"),teacher),v)).andExpect(status().isBadRequest());
            v.put("questions",List.of(Map.of("position",1,"title","q","content","c","maxChars",limit)));
            mvc.perform(body(request(post("/api/teacher/assessments"),teacher),v)).andExpect(status().isBadRequest());
        }
        var v=templateInput();v.put("questions",List.of(Map.of("position",1,"title","q","content","c")));
        var t=ok(body(request(post("/api/teacher/assessments"),teacher),v));assertThat(t.path("questions").get(0).path("minChars").isNull()).isTrue();
    }
    @Test void titleSelectionsStayIndependentOfRubricEditsAndExplicitEmptySelectionsRemainEmpty() throws Exception {
        var e=evaluations.create("선택 항목",60);String root="/api/teacher/evaluations/"+e.id();var v=templateInput();v.remove("title");v.remove("guidelines");
        ok(body(request(put(root+"/configuration"),teacher),v));
        ok(body(request(put(root+"/rubric"),teacher),Map.of("criteria",List.of(Map.of("name","직접 채점","maxScore",100)))));
        mvc.perform(request(get(root+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("criteria[0]").value("근거의 적절성"));
        v.put("criteria",List.of());ok(body(request(put(root+"/configuration"),teacher),v));
        mvc.perform(request(get(root+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("criteria").isEmpty());
        mvc.perform(request(get(root+"/rubric"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("criteria[0].name").value("직접 채점"));
    }

}
