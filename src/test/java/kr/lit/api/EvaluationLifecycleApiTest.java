package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 채점 화면 신규 API(2·7장), 평가 수정·삭제(3장), 경고 이벤트(5장), 종료 유예 저장. */
@TestPropertySource(properties="lit.evaluation.end-grace-seconds=30")
class EvaluationLifecycleApiTest extends AuthTestSupport {
    @Autowired AssessmentAnalyzer analyzer;

    record Fixture(String id,String q1,String q2,String pid,String token,String otherPid,String otherToken) {}
    Fixture fixture(AiPolicy policy) {
        as(teacher);var e=evaluations.create("AI 활용 평가",60);
        var config=work.configure(e.id(),new Configure(policy,List.of(new QuestionInput(1,"문항 1","광합성을 설명하세요."),new QuestionInput(2,"문항 2","예시를 드세요."))));
        var p=evaluations.join(e.pin(),"이하랑",null);var o=evaluations.join(e.pin(),"김별",null);evaluations.start(e.id());
        return new Fixture(e.id(),config.questions().get(0).id(),config.questions().get(1).id(),p.participantId(),p.participantToken(),o.participantId(),o.participantToken());
    }
    static List<String> texts(JsonNode array,String field) {
        var out=new ArrayList<String>();for(var n:array) out.add(n.path(field).asText());return out;
    }
    String root(Fixture f) { return "/api/teacher/evaluations/"+f.id(); }
    void submit(String id,String token) { var p=work.review(id,token);work.submit(id,token,p.reviewToken()); }
    String ask(Fixture f,String q,String requestId,String text) {
        var r=work.reserve(f.id(),f.token(),q,new Ask(requestId,text),"stub","local-stub");
        return work.finish(r,"광합성은 식물이 빛 에너지를 이용해 이산화탄소와 물로 포도당을 만드는 과정입니다",null).answerText();
    }

    @Test void evaluationPatchAndDeleteRespectState() throws Exception {
        as(teacher);var e=evaluations.create("원래 제목",60);String path="/api/teacher/evaluations/"+e.id();
        mvc.perform(body(request(patch(path),teacher),Map.of("title","새 제목","durationMinutes",45))).andExpect(status().isOk())
                .andExpect(jsonPath("title").value("새 제목")).andExpect(jsonPath("durationMinutes").value(45));
        mvc.perform(body(request(patch(path),teacher),Map.of())).andExpect(status().isBadRequest());
        work.configure(e.id(),new Configure(null,List.of(new QuestionInput(1,"q","c")),null,null,4));
        mvc.perform(request(get(path+"/questions"),teacher)).andExpect(jsonPath("aiPolicy").value("TASK_ASSIST")).andExpect(jsonPath("aiLevel").value(4));
        evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        mvc.perform(body(request(patch(path),teacher),Map.of("title","x"))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("EVALUATION_LOCKED"));
        mvc.perform(request(delete(path),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("EVALUATION_IN_PROGRESS"));
        as(teacher);evaluations.end(e.id());
        mvc.perform(request(delete(path),teacher)).andExpect(status().isNoContent());
        mvc.perform(request(get(path),teacher)).andExpect(status().isNotFound());
    }

    @Test void autoSubmittedStudentCanSaveDuringGraceButManualSubmitterCannot() throws Exception {
        var f=fixture(AiPolicy.DISABLED);
        work.save(f.id(),f.token(),f.q1(),new SaveAnswer("첫 저장",0,AnswerSource.DIRECT,null));
        submit(f.id(),f.otherToken());
        as(teacher);evaluations.end(f.id());
        var saved=work.save(f.id(),f.token(),f.q1(),new SaveAnswer("마지막 입력",1,AnswerSource.DIRECT,null));
        assertThat(saved.version()).isEqualTo(2);
        var submission=work.submission(f.id(),f.token());
        assertThat(submission.submissionMode()).isEqualTo("AUTO_TEACHER_END");
        assertThat(submission.snapshot().path("answers").get(0).path("answer").path("content").asText()).isEqualTo("마지막 입력");
        assertThatThrownBy(()->work.save(f.id(),f.otherToken(),f.q1(),new SaveAnswer("늦음",0,AnswerSource.DIRECT,null)))
                .isInstanceOfSatisfying(ApiError.Failure.class,ex->assertThat(ex.code).isEqualTo("ALREADY_SUBMITTED"));
        db.update("UPDATE evaluations SET ended_at=DATEADD('SECOND',-31,CURRENT_TIMESTAMP) WHERE id=?",f.id());
        assertThatThrownBy(()->work.save(f.id(),f.token(),f.q1(),new SaveAnswer("유예 후",2,AnswerSource.DIRECT,null)))
                .isInstanceOfSatisfying(ApiError.Failure.class,ex->assertThat(ex.code).isEqualTo("ALREADY_SUBMITTED"));
    }

}
