package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ParticipantReconnectApiTest extends AuthTestSupport {
    record Fixture(String id,String pid,String token,String question) {}
    Fixture fixture(boolean started) {
        as(teacher);var e=evaluations.create("복귀 평가",60);
        var c=work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"문항","설명하세요."))));
        var p=evaluations.join(e.pin(),"기존 학생",null);
        if(started)evaluations.start(e.id());
        return new Fixture(e.id(),p.participantId(),p.participantToken(),c.questions().get(0).id());
    }
    Map<String,String> input(Fixture f){return Map.of("evaluationId",f.id(),"participantToken",f.token());}
    com.fasterxml.jackson.databind.JsonNode reconnect(Fixture f) throws Exception {
        return ok(body(request(post("/api/participation/reconnect"),null),input(f)));
    }
    @Test void inactiveParticipantReturnsWithSameIdentitySavedAnswersAndVersion() throws Exception {
        var f=fixture(true);work.save(f.id(),f.token(),f.question(),new SaveAnswer("마지막 저장 답안",0,AnswerSource.DIRECT,null));
        var ends=db.queryForObject("SELECT ends_at FROM evaluations WHERE id=?",java.time.OffsetDateTime.class,f.id());
        evaluations.participant(f.id(),f.token(),true);
        var response=reconnect(f);
        assertThat(response.path("participantId").asText()).isEqualTo(f.pid());assertThat(response.path("readOnly").asBoolean()).isFalse();
        assertThat(response.at("/lobby/screen").asText()).isEqualTo("SOLVING");
        assertThat(response.at("/workspace/questions/0/answer/content").asText()).isEqualTo("마지막 저장 답안");
        assertThat(response.at("/workspace/questions/0/answer/version").asInt()).isEqualTo(1);
        assertThat(response.has("participantToken")).isFalse();reconnect(f);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM participants WHERE evaluation_id=?",Integer.class,f.id())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT ends_at FROM evaluations WHERE id=?",java.time.OffsetDateTime.class,f.id())).isEqualTo(ends);
    }
    @Test void waitingParticipantReturnsWithoutStartingOrLoadingQuestions() throws Exception {
        var f=fixture(false);evaluations.participant(f.id(),f.token(),true);
        var response=reconnect(f);
        assertThat(response.at("/lobby/screen").asText()).isEqualTo("WAITING");assertThat(response.path("workspace").isNull()).isTrue();
        assertThat(db.queryForObject("SELECT started_at FROM evaluations WHERE id=?",java.time.OffsetDateTime.class,f.id())).isNull();
    }
    @Test void endedAndExpiredParticipantsCanReturnReadOnlyAndCannotSave() throws Exception {
        var f=fixture(true);work.save(f.id(),f.token(),f.question(),new SaveAnswer("마감 전 답안",0,AnswerSource.DIRECT,null));
        evaluations.participant(f.id(),f.token(),true);
        db.update("UPDATE evaluations SET ends_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP),expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",f.id());
        var response=reconnect(f);
        assertThat(response.path("readOnly").asBoolean()).isTrue();assertThat(response.at("/workspace/status").asText()).isEqualTo("SUBMITTED");
        assertThat(response.at("/workspace/questions/0/answer/content").asText()).isEqualTo("마감 전 답안");
        mvc.perform(body(request(put("/api/evaluations/"+f.id()+"/questions/"+f.question()+"/answer"),null).header("X-Participant-Token",f.token()),new SaveAnswer("마감 후 입력",1,AnswerSource.DIRECT,null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("ALREADY_SUBMITTED"));
        reconnect(f);assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,f.pid())).isEqualTo(1);
    }
    @Test void submittedParticipantReturnsReadOnlyAndWrongTokensCannotActivateOthers() throws Exception {
        var f=fixture(true);var review=work.review(f.id(),f.token());work.submit(f.id(),f.token(),review.reviewToken());evaluations.participant(f.id(),f.token(),true);
        var foreign=fixture(true);
        mvc.perform(body(request(post("/api/participation/reconnect"),null),Map.of("evaluationId",f.id(),"participantToken",foreign.token())))
                .andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT active FROM participants WHERE id=?",Boolean.class,f.pid())).isFalse();
        var result=reconnect(f);assertThat(result.path("readOnly").asBoolean()).isTrue();assertThat(result.at("/lobby/screen").asText()).isEqualTo("SUBMITTED");
    }
    @Test void requiresTokenAndAppliesSharedParticipationRateLimit() throws Exception {
        var f=fixture(true);
        for(var invalid:List.of(Map.of("evaluationId",f.id(),"pin","123456"),Map.of("evaluationId",f.id(),"participantToken","invalid")))
            mvc.perform(body(request(post("/api/participation/reconnect"),null),invalid)).andExpect(status().isUnauthorized());
        remote="reconnect-limit-"+unique();
        for(int i=0;i<30;i++)mvc.perform(body(request(post("/api/participation/reconnect"),null),Map.of("evaluationId",f.id(),"participantToken","0".repeat(64)))).andExpect(status().isUnauthorized());
        mvc.perform(body(request(post("/api/participation/reconnect"),null),input(f))).andExpect(status().isTooManyRequests());
    }
}
