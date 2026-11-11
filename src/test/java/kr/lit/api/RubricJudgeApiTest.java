package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static kr.lit.api.SemanticJudgeClient.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 채점 흐름: 제출 → 답안 기여도 → (질문 용도) → 루브릭(Gemini 3번 → 엇갈리면 Haiku) → 평가 종료 후 결과 공개 → 교사 단계 확정.
 * 평가가 끝나기 전에는 결과를 내려 주지 않고(results-after-end=true), 반 진행률은 볼 수 있습니다.
 */
@TestPropertySource(properties={"lit.grading.results-after-end=true","lit.grading.judge.enabled=true","lit.grading.judge.anthropic.api-key=test","lit.grading.judge.anthropic.model=claude-test",
        "lit.grading.judge.gemini.analysis-key=test","lit.grading.judge.gemini.rubric-key=test-rubric","lit.grading.judge.gemini.model=gemini-test","lit.grading.job-interval-ms=3600000"})
class RubricJudgeApiTest extends AuthTestSupport {
    @Autowired AssessmentService assessment;
    @Autowired GradingQueue queue;
    @Autowired SemanticJudgeJob semanticJob;
    @Autowired PurposeClassificationJob purposeJob;
    @Autowired RubricJudgeJob rubricJob;
    @MockitoBean SemanticJudgeClient client;
    static final List<String> QUESTIONS=List.of("축제 방문객 통계는 누가 조사한 거야?","나는 상권 효과를 중심으로 쓰고 싶어. 근거를 비교해줘","서론에 축제 얘기를 넣을까?");
    static final String ANSWER="축제는 지역 상권을 살리는 데 효과가 있다고 나는 판단했다.";
    record Fixture(String id,String pid) {}
    /** 항목 이름별로 정한 Gemini 답(회차 순서대로). */
    final Map<String,List<Integer>> gemini=new ConcurrentHashMap<>();
    final Map<String,Integer> haiku=new ConcurrentHashMap<>();
    final Map<String,AtomicInteger> calls=new ConcurrentHashMap<>();

    static String criterion(String prompt){var m=java.util.regex.Pattern.compile("\\[루브릭 항목: ([^\\]]+)]").matcher(prompt);return m.find()?m.group(1):"";}
    static String answer(int level){return "{\"evidence\":[{\"src\":\"U\",\"quote\":\""+QUESTIONS.get(1)+"\"},{\"src\":\"ANS\",\"quote\":\""+ANSWER+"\"}],\"met\":\"충족한 조건\",\"blocked\":\"위 단계로 못 간 이유\",\"level\":"+level+"}";}
    String purposes(String data) throws Exception {
        var results=new ArrayList<Map<String,String>>();
        for(var item:json.readTree(data.substring(data.indexOf('{'))).path("items")) results.add(Map.of("id",item.path("id").asText(),"purpose","REVIEW"));
        return json.writeValueAsString(Map.of("results",results));
    }
    @BeforeEach void mocks() throws Exception {
        when(client.anthropicModel()).thenReturn("claude-test");when(client.geminiModel()).thenReturn("gemini-test");when(client.rubricModel()).thenReturn("gemini-test");
        when(client.geminiConfigured()).thenReturn(true);when(client.anthropicConfigured()).thenReturn(true);
        when(client.judgeWithHaiku(any())).thenAnswer(inv->{var out=new LinkedHashMap<String,Judgment>();for(Item i:inv.<List<Item>>getArgument(0))out.put(i.id(),new Judgment(0,null));return out;});
        when(client.callGemini(anyString(),anyString(),any(),isNull())).thenAnswer(inv->purposes(inv.getArgument(1)));
        when(client.callGemini(eq(GeminiKey.RUBRIC),anyString(),anyString(),isNull(),isNull())).thenAnswer(inv->{
            String name=criterion(inv.getArgument(2));int n=calls.computeIfAbsent(name,k->new AtomicInteger()).getAndIncrement();
            var levels=gemini.getOrDefault(name,List.of(4,4,4));return answer(levels.get(Math.min(n,levels.size()-1)));});
        when(client.callAnthropic(eq(RubricJudge.INSTRUCTIONS),anyString(),isNull())).thenAnswer(inv->answer(haiku.getOrDefault(criterion(inv.getArgument(1)),1)));
    }
    Fixture submit(List<String> questions) {
        as(teacher);var e=evaluations.create("루브릭 판정",60);
        var q=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"축제","지역 축제의 효과를 논하시오.")))).questions().get(0).id();
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        int i=0;for(String text:questions){var turn=work.reserve(e.id(),p.participantToken(),q,new Ask("r"+(++i),text),"stub","test");work.finish(turn,"AI 답변 "+i+": 축제는 지역 경제에 여러 효과가 있습니다.",null);}
        work.save(e.id(),p.participantToken(),q,new SaveAnswer("<p>"+ANSWER+"</p>",0,AnswerSource.DIRECT,null));
        var review=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),review.reviewToken());as(teacher);
        return new Fixture(e.id(),p.participantId());
    }
    /** 대기열 순서대로 이 학생의 작업을 처리합니다(예약 작업 대신 직접 호출). */
    void grade(Fixture f) {
        var requested=db.queryForObject("SELECT requested_at FROM grading_analyses WHERE participant_id=?",java.time.OffsetDateTime.class,f.pid());
        as(teacher);assessment.analyzeForJob(f.id(),f.pid(),requested);
        semanticJob.process(f.id(),f.pid());
        purposeJob.process(f.pid());
        var target=queue.ready(10_000).stream().filter(t->t.participantId().equals(f.pid())).findFirst().orElseThrow();
        rubricJob.process(target);
    }
    String root(Fixture f){return "/api/teacher/evaluations/"+f.id();}
    JsonNode criterion(JsonNode scores,int id){for(var c:scores.path("criteria"))if(c.path("criterionId").asInt()==id)return c;throw new AssertionError(id);}

    @Test void rubricIsJudgedAfterOtherStagesAndShownOnlyAfterTheEvaluationEnds() throws Exception {
        gemini.put("AI 답변의 비판적 검토",List.of(4,3,3));haiku.put("AI 답변의 비판적 검토",3);     // 엇갈림 → Haiku가 다수결과 같음 → 확정 3
        gemini.put("최종 답안에 대한 학생의 기여",List.of(2,3,4));haiku.put("최종 답안에 대한 학생의 기여",5); // 모두 다름 → 교사 직접 입력
        var f=submit(QUESTIONS);
        var before=ok(request(get(root(f)+"/grading-status"),teacher));
        assertThat(before.path("submitted").asInt()).isEqualTo(1);assertThat(before.at("/students/0/gradingStatus").asText()).isIn("QUEUED","GRADING");
        assertThat(queue.ready(10_000)).noneMatch(t->t.participantId().equals(f.pid())); // 분석 전에는 루브릭 대기열에 오르지 않습니다.

        grade(f);
        assertThat(ok(request(get(root(f)+"/grading-status"),teacher)).at("/students/0/gradingStatus").asText()).isEqualTo("DONE");
        // 평가 종료 전: 결과 비공개
        for(String path:List.of("/participants/"+f.pid()+"/rubric-scores","/participants/"+f.pid()+"/analysis","/students/"+f.pid()+"/usage-analysis"))
            mvc.perform(request(get(root(f)+path),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("GRADING_RESULTS_NOT_READY"));

        evaluations.end(f.id());
        var scores=ok(request(get(root(f)+"/participants/"+f.pid()+"/rubric-scores"),teacher));
        assertThat(scores.path("gradingStatus").asText()).isEqualTo("DONE");
        var purpose=criterion(scores,1).path("judgment");
        assertThat(purpose.path("status").asText()).isEqualTo("OK");assertThat(purpose.path("level").asInt()).isEqualTo(4);
        assertThat(purpose.path("score").asDouble()).isEqualTo(11.3);assertThat(purpose.path("needsReview").asBoolean()).isFalse();
        assertThat(purpose.path("votes").toString()).isEqualTo("[4,4,4]");assertThat(purpose.path("method").asText()).isEqualTo("LLM_GEMINI_X3");
        var evidence=purpose.at("/evidence/0");
        assertThat(evidence.path("src").asText()).isEqualTo("U");assertThat(evidence.path("messageId").asLong()).isPositive();assertThat(evidence.path("range").size()).isEqualTo(2);
        assertThat(purpose.path("met").asText()).isEqualTo("충족한 조건");assertThat(purpose.path("blocked").asText()).isEqualTo("위 단계로 못 간 이유");
        assertThat(criterion(scores,1).at("/ai/level").asInt()).isEqualTo(3); // 기존 ai 필드(0~4 척도)도 LLM 판정으로 채웁니다.
        var review=criterion(scores,3).path("judgment");
        assertThat(review.path("level").asInt()).isEqualTo(3);assertThat(review.path("haikuLevel").asInt()).isEqualTo(3);assertThat(review.path("method").asText()).isEqualTo("LLM_GEMINI_X3_HAIKU");
        var contribution=criterion(scores,4).path("judgment");
        assertThat(contribution.path("level").isNull()).isTrue();assertThat(contribution.path("needsReview").asBoolean()).isTrue();
        assertThat(contribution.path("reviewReason").asText()).isEqualTo("DISAGREEMENT");assertThat(contribution.path("runs").size()).isEqualTo(4);
        assertThat(criterion(scores,4).path("ai").isNull()).isTrue();
        assertThat(scores.path("reviewCount").asInt()).isEqualTo(1);assertThat(scores.path("maxTotal").asInt()).isEqualTo(100);
        assertThat(scores.path("missingAiCriteria").toString()).contains("최종 답안에 대한 학생의 기여");

        // 교사 단계 확정(1~5): needsReview가 풀리고 점수 재계산, 보정 기록 저장
        var patched=ok(request(body(patch(root(f)+"/participants/"+f.pid()+"/criteria/4/level"),Map.of("level",4)),teacher));
        var confirmed=criterion(patched,4);
        assertThat(confirmed.at("/judgment/teacherLevel").asInt()).isEqualTo(4);assertThat(confirmed.at("/judgment/needsReview").asBoolean()).isFalse();
        assertThat(confirmed.at("/teacher/score").asDouble()).isEqualTo(18.8);assertThat(patched.path("reviewCount").asInt()).isZero();
        var label=db.queryForMap("SELECT indicator,teacher_level,ai_level,votes,haiku_level,needs_review,review_reason FROM rubric_labels WHERE participant_id=?",f.pid());
        assertThat(label).containsEntry("INDICATOR","CONTRIBUTION").containsEntry("TEACHER_LEVEL",4).containsEntry("VOTES","[2,3,4]").containsEntry("HAIKU_LEVEL","5")
                .containsEntry("NEEDS_REVIEW",true).containsEntry("REVIEW_REASON","DISAGREEMENT");
        mvc.perform(request(body(patch(root(f)+"/participants/"+f.pid()+"/criteria/4/level"),Map.of("level",6)),teacher)).andExpect(status().isBadRequest());
        mvc.perform(request(body(patch(root(f)+"/participants/"+f.pid()+"/criteria/4/level"),Map.of("level",3)),newTeacher())).andExpect(status().isNotFound());

        // 같은 입력은 다시 부르지 않습니다(캐시). 다시 제출 버전을 올려도 기록이 같으면 판정 호출 없음.
        int before5=calls.values().stream().mapToInt(AtomicInteger::get).sum();
        db.update("UPDATE grading_analyses SET requested_at=DATEADD('SECOND',1,requested_at),analysis_status='PENDING' WHERE participant_id=?",f.pid());
        grade(f);
        assertThat(calls.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(before5);
    }
    @Test void studentWithoutAiGetsNaExceptSelfDirectionAndTotalsExcludeNa() throws Exception {
        var f=submit(List.of());
        grade(f);evaluations.end(f.id());
        var scores=ok(request(get(root(f)+"/participants/"+f.pid()+"/rubric-scores"),teacher));
        for(int id:List.of(1,3,4,5)) {
            assertThat(criterion(scores,id).path("notApplicable").asBoolean()).isTrue();
            assertThat(criterion(scores,id).at("/judgment/status").asText()).isEqualTo("NA");assertThat(criterion(scores,id).at("/judgment/score").isNull()).isTrue();
        }
        assertThat(criterion(scores,2).at("/judgment/level").asInt()).isEqualTo(4);
        assertThat(scores.path("maxTotal").asInt()).isEqualTo(25);assertThat(scores.path("missingAiCriteria").size()).isZero();
        verify(client,never()).callGemini(eq(GeminiKey.RUBRIC),anyString(),contains("[루브릭 항목: AI 활용 목적의 적절성]"),any(),any());
        // NA 항목 없이 확정할 수 있고, 총점은 판정된 항목만(25점 만점)입니다.
        ok(request(body(patch(root(f)+"/participants/"+f.pid()+"/criteria/2/level"),Map.of("level",5)),teacher));
        var done=ok(request(post(root(f)+"/participants/"+f.pid()+"/grade/confirm"),teacher));
        assertThat(done.path("status").asText()).isEqualTo("CONFIRMED");assertThat(done.path("confirmedTotal").asDouble()).isEqualTo(25.0);
    }
    @Test void tooFewQuestionsSkipTheModelAndFailuresAreRetriedThenMarkedFailed() throws Exception {
        var few=submit(QUESTIONS.subList(0,2));
        grade(few);evaluations.end(few.id());
        var scores=ok(request(get(root(few)+"/participants/"+few.pid()+"/rubric-scores"),teacher));
        assertThat(criterion(scores,1).at("/judgment/reviewReason").asText()).isEqualTo("INSUFFICIENT_DATA");assertThat(scores.path("reviewCount").asInt()).isEqualTo(5);
        verify(client,never()).callGemini(eq(GeminiKey.RUBRIC),anyString(),anyString(),any(),any());

        when(client.callGemini(eq(GeminiKey.RUBRIC),anyString(),anyString(),isNull(),isNull())).thenThrow(new JudgeFailure("JUDGE_GEMINI_RATE_LIMITED"));
        var f=submit(QUESTIONS);
        grade(f);
        assertThat(db.queryForMap("SELECT status,attempts,error_code FROM rubric_runs WHERE participant_id=?",f.pid()))
                .containsEntry("STATUS","FAILED").containsEntry("ATTEMPTS",1).containsEntry("ERROR_CODE","JUDGE_GEMINI_RATE_LIMITED");
        assertThat(queue.status(f.pid())).isEqualTo("GRADING"); // 1분 뒤 다시 시도
        db.update("UPDATE rubric_runs SET attempts=3 WHERE participant_id=?",f.pid());
        var progress=ok(request(get(root(f)+"/grading-status"),teacher));
        assertThat(progress.at("/students/0/gradingStatus").asText()).isEqualTo("FAILED");assertThat(progress.path("failed").asInt()).isEqualTo(1);
        // 실패해도 채점 화면은 열리고(기존 추천 단계), 교사가 다시 시도할 수 있습니다.
        evaluations.end(f.id());
        mvc.perform(request(get(root(f)+"/participants/"+f.pid()+"/rubric-scores"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("gradingStatus").value("FAILED"));
        var retried=ok(request(post(root(f)+"/participants/"+f.pid()+"/grading/retry"),teacher));
        assertThat(retried.at("/students/0/gradingStatus").asText()).isEqualTo("GRADING");
        assertThat(db.queryForObject("SELECT status FROM rubric_runs WHERE participant_id=?",String.class,f.pid())).isEqualTo("QUEUED");
    }
}
