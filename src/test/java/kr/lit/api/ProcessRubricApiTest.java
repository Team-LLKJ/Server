package kr.lit.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static kr.lit.api.ProcessGradingModels.*;
import static kr.lit.api.SemanticJudgeClient.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 루브릭 LLM 판정을 과정 채점(process-grading)에 붙인 흐름: 학생 단위 칸(questionId=null), PUT /review·POST /confirm,
 * 마지막 제출본만 채점·확정, NA 재분배(HOLD는 재분배하지 않음)·반올림, 학생 보고서는 확정 결과만.
 */
@TestPropertySource(properties={"lit.grading.judge.enabled=true","lit.grading.judge.anthropic.api-key=test","lit.grading.judge.anthropic.model=claude-test",
        "lit.grading.judge.gemini.analysis-key=test","lit.grading.judge.gemini.rubric-key=test-rubric","lit.grading.judge.gemini.model=gemini-test",
        "lit.grading.job-interval-ms=3600000","lit.grading.process-interval-ms=3600000","lit.evaluation.end-grace-seconds=600"})
class ProcessRubricApiTest extends AuthTestSupport {
    @Autowired ProcessGradingService service;
    @Autowired ProcessGradingRepository repository;
    @Autowired ProcessGradingWorker worker;
    @Autowired ProcessRubricGrader grader;
    @Autowired ReportGradingService reports;
    @Autowired AssessmentService assessment;
    @Autowired SemanticJudgeJob semanticJob;
    @Autowired PurposeClassificationJob purposeJob;
    @MockitoBean SemanticJudgeClient client;

    static final List<String> QUESTIONS=List.of("축제 방문객 통계는 누가 조사한 거야?","나는 상권 효과를 중심으로 쓰고 싶어. 근거를 비교해줘","결론에 넣을 실행 방안을 정리해줘");
    static final String ANSWER1="축제는 지역 상권을 살리는 데 효과가 있다고 나는 판단했다.",ANSWER2="그래서 셔틀버스와 지역 상품권을 함께 운영해야 한다.";
    /** 항목 이름 → Gemini 회차별 답("NA"·"HOLD" 가능). 없으면 4. */
    final Map<String,List<Object>> gemini=new ConcurrentHashMap<>();
    final Map<String,Integer> haiku=new ConcurrentHashMap<>();
    final AtomicInteger rubricCalls=new AtomicInteger();
    final Map<String,AtomicInteger> perCriterion=new ConcurrentHashMap<>();
    record Fixture(String id,String pid,String token,List<String> questions) {}

    static String criterion(String prompt){var m=java.util.regex.Pattern.compile("\\[루브릭 항목: ([^\\]]+)]").matcher(prompt);return m.find()?m.group(1):"";}
    static String answer(Object level){
        return "{\"evidence\":[{\"src\":\"U\",\"quote\":\""+QUESTIONS.get(1)+"\"},{\"src\":\"ANS\",\"quote\":\""+ANSWER1+"\"}],\"met\":\"충족한 조건\",\"blocked\":\"위 단계로 못 간 이유\",\"level\":"
                +(level instanceof Integer?level:"\""+level+"\"")+"}";
    }
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
            rubricCalls.incrementAndGet();String name=criterion(inv.getArgument(2));int n=perCriterion.computeIfAbsent(name,k->new AtomicInteger()).getAndIncrement();
            var levels=gemini.getOrDefault(name,List.of(4));return answer(levels.get(n%levels.size()));});
        when(client.callAnthropic(eq(RubricJudge.INSTRUCTIONS),anyString(),isNull())).thenAnswer(inv->answer(haiku.getOrDefault(criterion(inv.getArgument(1)),1)));
    }
    /** 문항 2개. AI 질문은 문항 1에 두 개, 문항 2에 나머지. 제출하지 않고 교사 종료로 자동 제출합니다. */
    Fixture endWithAutoSubmission(List<String> questions) {
        as(teacher);var e=evaluations.create("과정 채점 루브릭",60);
        var config=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"축제 효과","지역 축제의 효과를 논하시오."),new QuestionInput(2,"실행 방안","실행 방안을 제시하시오."))));
        var qs=config.questions().stream().map(q->q.id()).toList();
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        int i=0;for(String text:questions){String q=qs.get(i<2?0:1);var turn=work.reserve(e.id(),p.participantToken(),q,new Ask("r"+(++i),text),"stub","test");work.finish(turn,"AI 답변 "+i+": 축제는 지역 경제에 효과가 있습니다.",null);}
        work.save(e.id(),p.participantToken(),qs.get(0),new SaveAnswer("<p>"+ANSWER1+"</p>",0,AnswerSource.DIRECT,null));
        work.save(e.id(),p.participantToken(),qs.get(1),new SaveAnswer("<p>"+ANSWER2+"</p>",0,AnswerSource.DIRECT,null));
        as(teacher);evaluations.end(e.id());
        return new Fixture(e.id(),p.participantId(),p.participantToken(),qs);
    }
    Job latest(Fixture f){as(teacher);return service.getRuns(f.id(),f.pid()).get(0);}
    String runs(Fixture f){return "/api/teacher/evaluations/"+f.id()+"/process-grading/participants/"+f.pid()+"/runs";}
    Cell cell(Result r,Criterion c){return r.cells().stream().filter(x->x.criterion()==c).findFirst().orElseThrow();}
    LevelOverride level(Criterion c,int level){return new LevelOverride(null,c,level,"교사가 기록을 다시 확인했습니다.");}

    @Test void multiQuestionResubmissionGradesAndConfirmsOnlyTheLatestSubmission() throws Exception {
        var f=endWithAutoSubmission(QUESTIONS);
        var first=latest(f);assertThat(first.status()).isEqualTo("PENDING");
        // ①② 결과(답안 기여도·질문 용도)가 끝나야 루브릭을 시작합니다.
        assertThat(grader.ready(f.pid(),java.time.Instant.now())).isFalse();
        var requested=db.queryForObject("SELECT requested_at FROM grading_analyses WHERE participant_id=?",java.time.OffsetDateTime.class,f.pid());
        assessment.analyzeForJob(f.id(),f.pid(),requested);semanticJob.process(f.id(),f.pid());purposeJob.process(f.pid());
        assertThat(grader.ready(f.pid(),java.time.Instant.now())).isTrue();

        worker.processJob(first.id());first=latest(f);
        assertThat(first.status()).isEqualTo("DONE");assertThat(first.model()).isEqualTo("gemini-test×3+claude-test");
        assertThat(first.result().cells()).hasSize(5).allMatch(c->c.questionId()==null&&c.level()==4&&c.status().equals("RECOMMENDED"));
        assertThat(first.result().total()).isEqualByComparingTo("75"); // 11+19+15+19+11 (항목마다 정수로 반올림한 뒤 합)
        var purpose=cell(first.result(),Criterion.PURPOSE);
        assertThat(purpose.score()).isEqualByComparingTo("11");assertThat(purpose.adjustedMaxScore()).isEqualTo(15);assertThat(purpose.judge().votes()).containsExactly(4,4,4);assertThat(purpose.judge().needsReview()).isFalse();
        var citation=purpose.conditions().get(0).evidence().stream().filter(c->c.location().conversationId()!=null).findFirst().orElseThrow();
        assertThat(citation.quote()).isEqualTo(QUESTIONS.get(1));assertThat(citation.location().messageRole()).isEqualTo("USER");
        assertThat(rubricCalls.get()).isEqualTo(15); // 학생 한 명 × 5항목 × 3번

        // 종료 후 유예 시간에 문항 2를 두 번 고쳐 저장(자동 제출본 갱신) → 새 작업. 시작 전 이전 작업은 SUPERSEDED.
        work.save(f.id(),f.token(),f.questions().get(1),new SaveAnswer("<p>"+ANSWER2+" 예산은 구청과 나눈다.</p>",1,AnswerSource.DIRECT,null));
        var second=latest(f);assertThat(second.version()).isEqualTo(2);assertThat(second.status()).isEqualTo("PENDING");
        work.save(f.id(),f.token(),f.questions().get(1),new SaveAnswer("<p>"+ANSWER2+" 예산은 구청과 절반씩 나눈다.</p>",2,AnswerSource.DIRECT,null));
        var third=latest(f);as(teacher);
        assertThat(service.getRuns(f.id(),f.pid())).extracting(Job::status).containsExactly("PENDING","SUPERSEDED","DONE");

        // 이전 제출본의 결과는 검토·확정할 수 없습니다.
        mvc.perform(body(request(put(runs(f)+"/"+first.id()+"/review"),teacher),new ReviewRequest(0,List.of(),"CONFIRMED_OK","확인")))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("PROCESS_RESULT_STALE"));
        worker.processJob(third.id());third=latest(f);
        assertThat(third.status()).isEqualTo("DONE");

        // 학생 단위 칸은 questionId=null로 보정합니다.
        String path=runs(f)+"/"+third.id();
        var review=ok(body(request(put(path+"/review"),teacher),new ReviewRequest(0,List.of(new LevelOverride(null,Criterion.CONTRIBUTION,5,null)),null,null)));
        assertThat(review.at("/result/total").decimalValue()).isEqualByComparingTo("81"); // 11+19+15+25+11
        var reviewed=review.path("result").path("cells").get(3);
        assertThat(reviewed.path("questionId").isNull()).isTrue();assertThat(reviewed.path("status").asText()).isEqualTo("TEACHER_REVIEWED");assertThat(reviewed.path("score").decimalValue()).isEqualByComparingTo("25");assertThat(reviewed.path("adjustedMaxScore").asInt()).isEqualTo(25);
        mvc.perform(body(request(put(path+"/review"),teacher),new ReviewRequest(1,List.of(new LevelOverride("not-a-question",Criterion.PURPOSE,3,"잘못된 칸")),"CONFIRMED_OK","확인")))
                .andExpect(status().isBadRequest());

        // 학생 보고서: 확정 전에는 점수·AI 추천 없음, 확정 후 확정 점수
        var draft=reports.getSnapshot(f.id(),f.pid());
        assertThat(draft.status()).isEqualTo("DRAFT");assertThat(draft.criteria()).allMatch(c->c.score()==null);assertThat(draft.aiRecommendation()).isNull();
        mvc.perform(body(request(post(runs(f)+"/"+first.id()+"/confirm"),teacher),new ConfirmRequest(1))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("PROCESS_RESULT_STALE"));
        mvc.perform(body(request(post(path+"/confirm"),teacher),new ConfirmRequest(1))).andExpect(status().isOk()).andExpect(jsonPath("confirmed").value(true));
        var confirmed=reports.getSnapshot(f.id(),f.pid());
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.criteria()).extracting(ReportModels.GradingCriterion::score).containsExactly(11.0,19.0,15.0,25.0,11.0);
        assertThat(confirmed.teacherReview().result().partialScore().assessedScore()).isEqualByComparingTo("81");
        assertThat(confirmed.evaluationSummary().strengths()).anyMatch(entry->QUESTIONS.get(1).equals(entry.quote()));
        var analysis=ok(request(get(runs(f).replace("/runs","/analysis")),teacher));
        assertThat(json.treeToValue(analysis.path("summary"),ProcessGradingSummary.class)).isEqualTo(confirmed.evaluationSummary());
    }
    @Test void selfDirectionTeacherInputRedistributesNaAndRounds() throws Exception {
        // AI를 쓰지 않은 학생: 자기 주도적 사고는 모델 없이 교사 확인(HOLD·NO_AI_USE), 나머지 4개는 NA
        var f=endWithAutoSubmission(List.of());
        var job=latest(f);worker.processJob(job.id());job=latest(f);
        var r=job.result();
        assertThat(r.status()).isEqualTo("HOLD");assertThat(r.total()).isNull();
        for(var c:List.of(Criterion.PURPOSE,Criterion.CRITICAL_REVIEW,Criterion.CONTRIBUTION,Criterion.TRANSPARENCY)) assertThat(cell(r,c).status()).isEqualTo("NOT_APPLICABLE");
        var sd=cell(r,Criterion.SELF_DIRECTION);
        assertThat(sd.status()).isEqualTo("HOLD");assertThat(sd.level()).isNull();assertThat(sd.judge().needsReview()).isTrue();assertThat(sd.judge().reviewReason()).isEqualTo("NO_AI_USE");
        assertThat(sd.judge().votes()).isEmpty();assertThat(sd.judge().haikuLevel()).isNull();assertThat(sd.judge().method()).isEqualTo("SERVER_RULE");
        assertThat(ProcessGradingSummary.fromResult(r).checks()).anyMatch(c->c.startsWith("자기 주도적 사고"));
        assertThat(r.partialScore().heldMaxScore()).isEqualByComparingTo("100");assertThat(r.partialScore().notApplicableMaxScore()).isEqualByComparingTo("75");
        assertThat(sd.adjustedMaxScore()).isEqualTo(100);assertThat(sd.score()).isNull(); // HOLD 몫은 교사가 채울 때까지 비움
        assertThat(rubricCalls.get()).isZero(); // 모델을 부르지 않음
        String path=runs(f)+"/"+job.id();
        // 규정 준수 선택(complianceDecision)·사유는 선택 항목입니다.
        mvc.perform(body(request(put(path+"/review"),teacher),new ReviewRequest(0,List.of(),null,null))).andExpect(status().isOk());
        mvc.perform(body(request(post(path+"/confirm"),teacher),new ConfirmRequest(1))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("PROCESS_CONFIRMATION_HELD"));

        // 교사가 자기 주도 4단계 입력: 25점 항목 하나가 재분배 배점 100점 → 100 × 3/4 = 75
        var review=ok(body(request(put(path+"/review"),teacher),new ReviewRequest(1,List.of(level(Criterion.SELF_DIRECTION,4)),"CONFIRMED_OK","확인")));
        assertThat(review.at("/result/total").decimalValue()).isEqualByComparingTo("75");assertThat(review.at("/result/status").asText()).isEqualTo("DONE");
        assertThat(review.at("/result/cells/1/score").decimalValue()).isEqualByComparingTo("75");assertThat(review.at("/result/cells/1/adjustedMaxScore").asInt()).isEqualTo(100);
        assertThat(review.at("/result/cells/1/judge/needsReview").asBoolean()).isFalse();
        // 점수를 직접 넣으면 원래 배점 기준 정수로 반올림(13.33 → 13) 후 재분배 배점 비율로 환산: 13 × 100/25 = 52
        review=ok(body(request(put(path+"/review"),teacher),new ReviewRequest(2,List.of(LevelOverride.ofScore(null,Criterion.SELF_DIRECTION,new BigDecimal("13.33"),"직접 점수")),"CONFIRMED_OK","확인")));
        assertThat(review.at("/result/total").decimalValue()).isEqualByComparingTo("52");
        assertThat(review.at("/result/partialScore/assessedMaxScore").decimalValue()).isEqualByComparingTo("100");
        mvc.perform(body(request(post(path+"/confirm"),teacher),new ConfirmRequest(3))).andExpect(status().isOk());
        var report=reports.getSnapshot(f.id(),f.pid());
        assertThat(report.criteria()).filteredOn(ReportModels.GradingCriterion::notApplicable).hasSize(4);
        assertThat(report.criteria().get(1).score()).isEqualTo(52.0);assertThat(report.criteria().get(1).maxScore()).isEqualTo(100);
    }
    @Test void holdIsNotRedistributedButNaIs() throws Exception {
        gemini.put("AI 활용 목적의 적절성",List.of("NA"));                       // 모델이 해당 없음 → 배점 재분배
        gemini.put("AI 답변의 비판적 검토",List.of(4,4,3));haiku.put("AI 답변의 비판적 검토",5); // 과반(4)과 Haiku(5)가 다름 → HOLD(재분배 안 함)
        var f=endWithAutoSubmission(QUESTIONS);
        var job=latest(f);worker.processJob(job.id());job=latest(f);
        var r=job.result();
        assertThat(cell(r,Criterion.PURPOSE).status()).isEqualTo("NOT_APPLICABLE");assertThat(cell(r,Criterion.CRITICAL_REVIEW).status()).isEqualTo("HOLD");
        // 기준 85점(100 − NA 15)을 100으로 환산. HOLD 20점은 자기 몫(23.5)을 그대로 남겨 둡니다.
        var p=r.partialScore();
        assertThat(p.notApplicableMaxScore()).isEqualByComparingTo("15");
        assertThat(p.heldMaxScore()).isEqualByComparingTo("24");                  // 20 × 100/85 = 23.5 → 24
        assertThat(p.assessedMaxScore()).isEqualByComparingTo("76");              // 29 + 29 + 18
        assertThat(p.assessedScore()).isEqualByComparingTo("58");                 // 22 + 22 + 14 (29×3/4=21.75→22, 18×3/4=13.5→14)
        assertThat(r.cells()).extracting(Cell::adjustedMaxScore).containsExactly(0,29,24,29,18); // 재분배 배점 합 100
        assertThat(r.total()).isNull();assertThat(r.status()).isEqualTo("HOLD");
        String path=runs(f)+"/"+job.id();
        // 보정 사유는 비워도 됩니다. 규정 준수 HOLD여도 학생 단위 결과는 확정할 수 있습니다.
        var review=ok(body(request(put(path+"/review"),teacher),new ReviewRequest(0,List.of(new LevelOverride(null,Criterion.CRITICAL_REVIEW,4,"")),"HOLD",null)));
        assertThat(review.at("/result/total").decimalValue()).isEqualByComparingTo("76");     // 58 + 24×3/4=18
        assertThat(review.at("/result/cells/2/reason").asText()).isEmpty();
        assertThat(review.at("/result/partialScore/heldMaxScore").decimalValue()).isEqualByComparingTo("0");
        mvc.perform(body(request(post(path+"/confirm"),teacher),new ConfirmRequest(1))).andExpect(status().isOk());
    }
}
