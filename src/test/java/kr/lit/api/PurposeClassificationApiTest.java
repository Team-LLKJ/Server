package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static kr.lit.api.SemanticJudgeClient.JudgeFailure;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 질문 용도: 백그라운드 Haiku 3회 → 불일치만 Gemini → usage-analysis, 교사 확정 PATCH, 실패 시 키워드 대체. */
@TestPropertySource(properties={"lit.grading.judge.enabled=true","lit.grading.judge.anthropic.api-key=test","lit.grading.judge.anthropic.model=claude-test",
        "lit.grading.judge.gemini.api-key=test","lit.grading.judge.gemini.model=gemini-test"})
class PurposeClassificationApiTest extends AuthTestSupport {
    @Autowired PurposeClassificationJob job;
    @MockitoBean SemanticJudgeClient client;
    static final String Q1="광합성이 뭐야?",Q2="방금 말한 40%는 누가 센 거야?",Q3="이 문장 어색한 부분 고쳐줘: 식물은 빛을 먹는다";
    static final String LONG_ANSWER="가".repeat(500);
    record Fixture(String id,String pid) {}

    @BeforeEach void models(){when(client.anthropicModel()).thenReturn("claude-test");when(client.geminiModel()).thenReturn("gemini-test");when(client.geminiConfigured()).thenReturn(true);}
    Fixture fixture() {
        as(teacher);var e=evaluations.create("용도 분류",60);
        var q=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","광합성을 설명하세요.")))).questions().get(0).id();
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        int i=0;for(String text:List.of(Q1,Q2,Q3)){var turn=work.reserve(e.id(),p.participantToken(),q,new Ask("r"+(++i),text),"stub","test");work.finish(turn,LONG_ANSWER,null);}
        as(teacher);return new Fixture(e.id(),p.participantId());
    }
    /** 자료(JSON)의 항목마다 question 문구로 용도를 정해 답하는 모의 LLM. */
    String answer(String data,Function<String,String> purpose) throws Exception {
        var items=json.readTree(data.substring(data.indexOf('{'))).path("items");
        var results=new ArrayList<Map<String,String>>();
        for(var item:items) results.add(Map.of("id",item.path("id").asText(),"purpose",purpose.apply(item.path("question").asText())));
        return json.writeValueAsString(Map.of("results",results));
    }
    static List<String> texts(JsonNode array){var out=new ArrayList<String>();for(var v:array)out.add(v.asText());return out;}
    static List<String> texts(JsonNode array,String field){var out=new ArrayList<String>();for(var v:array)out.add(v.path(field).asText());return out;}
    String usagePath(Fixture f){return "/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/usage-analysis";}
    JsonNode question(JsonNode view,String text){for(var q:view.path("questions"))if(q.path("quote").asText().equals(text))return q;throw new AssertionError(text);}

    @Test void haikuThreeVotesThenGeminiOnlyForDisagreementsAndTeacherConfirms() throws Exception {
        var call=new AtomicInteger();
        when(client.callAnthropic(anyString(),anyString(),isNull())).thenAnswer(inv->{int n=call.getAndIncrement();
            return answer(inv.getArgument(1),q->q.equals(Q1)?"UNDERSTAND":q.equals(Q3)?"EDIT":n==2?"UNDERSTAND":"REVIEW");});
        when(client.callGemini(anyString(),anyString(),any(),isNull())).thenAnswer(inv->answer(inv.getArgument(1),q->"REVIEW"));
        var f=fixture();
        var before=ok(request(get(usagePath(f)),teacher));
        assertThat(before.path("pendingCount").asInt()).isEqualTo(3);assertThat(before.path("method").asText()).isEqualTo("KEYWORD_FALLBACK");

        job.process(f.pid());
        var view=ok(request(get(usagePath(f)),teacher));
        assertThat(question(view,Q1).path("purpose").asText()).isEqualTo("UNDERSTAND");assertThat(question(view,Q1).path("needsReview").asBoolean()).isFalse();
        var q2=question(view,Q2);
        assertThat(q2.path("purpose").asText()).isEqualTo("REVIEW");assertThat(q2.path("needsReview").asBoolean()).isTrue();
        assertThat(q2.path("geminiPurpose").asText()).isEqualTo("REVIEW");assertThat(q2.path("method").asText()).isEqualTo("LLM_HAIKU_X3_GEMINI");
        assertThat(texts(q2.path("votes"))).containsExactly("REVIEW","REVIEW","UNDERSTAND");
        assertThat(question(view,Q3).path("purpose").asText()).isEqualTo("EDIT");
        assertThat(view.path("reviewCount").asInt()).isEqualTo(1);assertThat(view.path("pendingCount").asInt()).isZero();
        double sum=0;for(var g:view.path("usage"))sum+=g.path("percentage").asDouble();assertThat(sum).isCloseTo(100.0,within(1e-9));
        assertThat(texts(view.path("usage"),"name")).containsExactly("이해·조사","비판적 검토","표현 수정");

        // Haiku는 같은 입력으로 3번(기본 temperature), 맥락은 앞 질문 2개 + 직전 AI 답변 200자. Gemini는 불일치 질문만.
        var data=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client,times(3)).callAnthropic(eq(PurposeClassifier.INSTRUCTIONS),data.capture(),isNull());
        assertThat(new HashSet<>(data.getAllValues())).hasSize(1);
        var items=json.readTree(data.getValue().substring(data.getValue().indexOf('{'))).path("items");
        var third=items.get(2);
        assertThat(texts(third.path("previous"))).containsExactly(Q1,Q2);assertThat(third.path("previousAiAnswer").asText()).hasSize(200);
        var geminiData=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client,times(1)).callGemini(eq(PurposeClassifier.INSTRUCTIONS),geminiData.capture(),any(),isNull());
        var geminiItems=json.readTree(geminiData.getValue().substring(geminiData.getValue().indexOf('{'))).path("items");
        assertThat(texts(geminiItems,"question")).containsExactly(Q2);

        // 다시 돌려도 같은 질문은 다시 호출하지 않습니다.
        job.process(f.pid());verify(client,times(3)).callAnthropic(anyString(),anyString(),isNull());

        // 교사 확정: 그 값으로 덮고 보정 기록 저장
        long q2Id=q2.path("conversationId").asLong();
        var patched=ok(request(body(patch("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/conversations/"+q2Id+"/purpose"),Map.of("purpose","UNDERSTAND")),teacher));
        var confirmed=question(patched,Q2);
        assertThat(confirmed.path("purpose").asText()).isEqualTo("UNDERSTAND");assertThat(confirmed.path("teacherPurpose").asText()).isEqualTo("UNDERSTAND");
        assertThat(confirmed.path("needsReview").asBoolean()).isFalse();assertThat(patched.path("reviewCount").asInt()).isZero();
        var label=db.queryForMap("SELECT teacher_purpose,llm_purpose,votes,gemini_purpose,needs_review FROM purpose_labels WHERE conversation_id=?",q2Id);
        assertThat(label).containsEntry("TEACHER_PURPOSE","UNDERSTAND").containsEntry("LLM_PURPOSE","REVIEW").containsEntry("VOTES","REVIEW,REVIEW,UNDERSTAND").containsEntry("NEEDS_REVIEW",true);

        // 채점 지표의 질문 분류도 같은 결과를 씁니다(교사 확정 UNDERSTAND → 개념 이해, LLM EDIT → 표현 수정).
        var conversations=ok(request(get("/api/teacher/evaluations/"+f.id()+"/participants/"+f.pid()+"/conversations"),teacher));
        assertThat(texts(conversations,"questionType")).containsExactly("CONCEPT_UNDERSTANDING","CONCEPT_UNDERSTANDING","EDIT");

        mvc.perform(request(body(patch("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/conversations/"+q2Id+"/purpose"),Map.of("purpose","MAYBE")),teacher)).andExpect(status().isBadRequest());
        mvc.perform(request(body(patch("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/conversations/999999/purpose"),Map.of("purpose","PLAN")),teacher)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("CONVERSATION_NOT_FOUND"));
        mvc.perform(request(body(patch("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/conversations/"+q2Id+"/purpose"),Map.of("purpose","PLAN")),newTeacher())).andExpect(status().isNotFound());
    }
    @Test void geminiFailureUsesMajorityOrUndecided() throws Exception {
        var call=new AtomicInteger();
        // Q1: A,A,B → 다수결 / Q2: 세 답이 모두 다름 → UNDECIDED / Q3: 일치
        when(client.callAnthropic(anyString(),anyString(),isNull())).thenAnswer(inv->{int n=call.getAndIncrement();
            return answer(inv.getArgument(1),q->q.equals(Q1)?(n<2?"PLAN":"UNDERSTAND"):q.equals(Q2)?List.of("PLAN","REVIEW","DRAFT").get(n):"EDIT");});
        when(client.callGemini(anyString(),anyString(),any(),isNull())).thenThrow(new JudgeFailure("JUDGE_GEMINI_HTTP_503"));
        var f=fixture();job.process(f.pid());
        var view=ok(request(get(usagePath(f)),teacher));
        assertThat(question(view,Q1).path("purpose").asText()).isEqualTo("PLAN");assertThat(question(view,Q1).path("needsReview").asBoolean()).isTrue();
        assertThat(question(view,Q2).path("purpose").asText()).isEqualTo("UNDECIDED");
        assertThat(view.path("undecidedCount").asInt()).isEqualTo(1);assertThat(texts(view.path("usage"),"purpose")).doesNotContain("UNDECIDED");
    }
    @Test void haikuFailureFallsBackToKeywordsAndRetries() throws Exception {
        when(client.callAnthropic(anyString(),anyString(),isNull())).thenThrow(new JudgeFailure("JUDGE_ANTHROPIC_TIMEOUT"));
        var f=fixture();job.process(f.pid());
        var view=ok(request(get(usagePath(f)),teacher));
        assertThat(view.path("method").asText()).isEqualTo("KEYWORD_FALLBACK");
        for(var q:view.path("questions"))assertThat(q.path("method").asText()).isEqualTo("KEYWORD_FALLBACK");
        assertThat(db.queryForList("SELECT status FROM purpose_classifications WHERE participant_id=?",String.class,f.pid())).containsOnly("FAILED");
        assertThat(db.queryForObject("SELECT MAX(attempts) FROM purpose_classifications WHERE participant_id=?",Integer.class,f.pid())).isEqualTo(1);
        verify(client,never()).callGemini(anyString(),anyString(),any(),any());
    }
}
