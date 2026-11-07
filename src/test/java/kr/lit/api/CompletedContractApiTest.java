package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static kr.lit.api.WorkModels.*;

class CompletedContractApiTest extends AuthTestSupport {
    Map<String,Object> settings(){return Map.of("questions",List.of(Map.of("position",1,"title","문항","content","문제")));}
    @Test void defaultHintOnlyAppliesToNewSettingsAndKeepsExplicitOldPolicies() throws Exception {
        as(teacher);var e=evaluations.create("새 기본값",60);
        mvc.perform(request(get("/api/teacher/evaluations/"+e.id()+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("aiPolicy").value("HINT_ONLY"));
        as(teacher);work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"문항","문제"))));
        var fresh=evaluations.create("다음 평가",60);
        mvc.perform(request(get("/api/teacher/evaluations/"+e.id()+"/configuration"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("aiPolicy").value("DISABLED"));
        mvc.perform(body(request(put("/api/teacher/evaluations/"+fresh.id()+"/questions"),teacher),settings())).andExpect(status().isOk()).andExpect(jsonPath("aiPolicy").value("HINT_ONLY"));
        var input=new LinkedHashMap<>(settings());input.put("title","기본 틀");
        mvc.perform(body(request(post("/api/teacher/assessments"),teacher),input)).andExpect(status().isCreated()).andExpect(jsonPath("aiPolicy").value("HINT_ONLY"));
    }
    @Test void frontendFullscreenAliasNormalizesAndBothEventSpellingsAreWarnings() throws Exception {
        as(teacher);var e=evaluations.create("이름 호환",60);var input=new LinkedHashMap<>(settings());input.put("antiCheat",List.of("FULLSCREEN","DEVTOOLS"));
        mvc.perform(body(request(put("/api/teacher/evaluations/"+e.id()+"/questions"),teacher),input)).andExpect(status().isOk()).andExpect(jsonPath("antiCheat[0]").value("FULLSCREEN_LEAVE"));
        input.put("antiCheat",List.of("FULLSCREEN","FULLSCREEN_LEAVE"));
        mvc.perform(body(request(put("/api/teacher/evaluations/"+e.id()+"/questions"),teacher),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_ANTI_CHEAT"));
        as(teacher);var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        for(String type:List.of("FULLSCREEN_LEAVE","FULLSCREEN_EXIT","DEVTOOLS_OPEN"))
            mvc.perform(body(post("/api/evaluations/"+e.id()+"/events").header("X-Participant-Token",p.participantToken()),Map.of("type",type,"occurredAt",java.time.Instant.now())))
                    .andExpect(status().isCreated()).andExpect(jsonPath("details.warning").value(true));
        mvc.perform(request(get("/api/teacher/evaluations/"+e.id()+"/students"),teacher)).andExpect(jsonPath("items[0].warningCount").value(3));
    }
    @Test void submittedStudentCanReadReportsAfterLeaveWithoutRestoringWriteAccess() throws Exception {
        as(teacher);var e=evaluations.create("제출 후 리포트",60);var config=work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"문항","문제"))));
        as(teacher);var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        var preview=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),preview.reviewToken());evaluations.participant(e.id(),p.participantToken(),true);
        String base="/api/evaluations/"+e.id();
        mvc.perform(get(base+"/report/hwp").header("X-Participant-Token",p.participantToken())).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("REPORT_NOT_FOUND"));
        mvc.perform(post(base+"/reports").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk());
        mvc.perform(get(base+"/report").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk());
        mvc.perform(get(base+"/report/hwp").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk()).andExpect(content().contentType("application/x-hwp"));
        mvc.perform(get(base+"/report/pdf").header("X-Participant-Token",p.participantToken())).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(get(base+"/questions").header("X-Participant-Token",p.participantToken())).andExpect(status().isUnauthorized());
        mvc.perform(body(put(base+"/questions/"+config.questions().get(0).id()+"/answer").header("X-Participant-Token",p.participantToken()),new SaveAnswer("금지",0,AnswerSource.DIRECT,null))).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT active FROM participants WHERE id=?",Boolean.class,p.participantId())).isFalse();
        as(teacher);var other=evaluations.create("타 평가",60);
        mvc.perform(get("/api/evaluations/"+other.id()+"/report").header("X-Participant-Token",p.participantToken())).andExpect(status().isUnauthorized());
    }
    @Test void inactiveUnsubmittedStudentCannotAccessReports() throws Exception {
        as(teacher);var e=evaluations.create("미제출",60);var p=evaluations.join(e.pin(),"학생",null);evaluations.participant(e.id(),p.participantToken(),true);
        mvc.perform(post("/api/evaluations/"+e.id()+"/reports").header("X-Participant-Token",p.participantToken())).andExpect(status().isUnauthorized());
    }
    @Test void missingProtectedPathAndWrongMethodHaveDistinctStableCodes() throws Exception {
        mvc.perform(request(get("/api/teacher/unknown-route"),teacher)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        mvc.perform(request(post("/api/teacher/unknown-route"),teacher)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        mvc.perform(request(put("/api/teacher/evaluations/unused/qr"),teacher)).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/teacher/unknown-route")).andExpect(status().isUnauthorized());
    }
    @Test void processCellsExposeDisplayNameAndMaxScoreWithoutChangingPersistedModel() throws Exception {
        var cell=new ProcessGradingModels.Cell("q",ProcessGradingModels.Criterion.PURPOSE,3,new BigDecimal("11.25"),"DONE","근거","다음 단계",List.of(),List.of(),List.of());
        var encoded=json.valueToTree(cell);assertThat(encoded.path("maxScore").asInt()).isEqualTo(15);assertThat(encoded.path("criterionName").asText()).isEqualTo("AI 활용 목적의 적절성");
        assertThat(json.treeToValue(encoded,ProcessGradingModels.Cell.class)).isEqualTo(cell);
        as(teacher);var e=evaluations.create("척도",60);
        mvc.perform(request(get("/api/teacher/evaluations/"+e.id()+"/process-grading/rubric"),teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("criteria[0].criterion").value("PURPOSE")).andExpect(jsonPath("criteria[0].name").value("AI 활용 목적의 적절성")).andExpect(jsonPath("criteria[0].maxScore").value(15));
    }
}
