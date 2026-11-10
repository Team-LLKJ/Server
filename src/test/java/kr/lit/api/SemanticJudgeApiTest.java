package kr.lit.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.function.Function;
import static kr.lit.api.SemanticJudgeClient.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 2단계 판정: 제출 → 분석 중(PENDING) → 백그라운드 Gemini·Sonnet 판정 → SEMANTIC 반영, 실패 시 1단계 결과 유지. */
@TestPropertySource(properties={"lit.grading.judge.enabled=true","lit.grading.judge.gemini.api-key=test","lit.grading.judge.gemini.model=gemini-test",
        "lit.grading.judge.anthropic.api-key=test","lit.grading.judge.anthropic.model=claude-test"})
class SemanticJudgeApiTest extends AuthTestSupport {
    @Autowired AssessmentService assessment;
    @Autowired SemanticJudgeJob job;
    @MockitoBean SemanticJudgeClient client;

    static final String AI="청년층은 더 많은 취업 기회를 찾아 수도권이나 대도시로 이동하는 경향이 강합니다. 그래서 지방의 고령화가 빨라집니다.";
    static final String PARAPHRASE="젊은 사람들은 취직할 곳이 많은 서울 근처나 큰 도시로 떠나는 경우가 많다.";
    static final String OWN="우리 동네 버스는 하루에 네 번만 다녀서 불편하다.";
    record Fixture(String id,String pid) {}

    @BeforeEach void models() {
        when(client.geminiModel()).thenReturn("gemini-test");when(client.anthropicModel()).thenReturn("claude-test");when(client.anthropicConfigured()).thenReturn(true);
    }
    Fixture submit(String answer) {
        as(teacher);var e=evaluations.create("출처 판정",60);
        var q=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","지방 인구 감소의 원인을 설명하세요.")))).questions().get(0).id();
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        var turn=work.reserve(e.id(),p.participantToken(),q,new Ask("r1","지방 인구가 왜 줄어드는지 설명해줘"),"stub","test");work.finish(turn,AI,null);
        work.save(e.id(),p.participantToken(),q,new SaveAnswer("<p>"+PARAPHRASE+" "+OWN+"</p>",0,AnswerSource.DIRECT,null));
        var review=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),review.reviewToken());as(teacher);
        assessment.analysis(e.id(),p.participantId()); // 제출 후 1단계 분석(실제로는 GradingAnalysisJob)
        return new Fixture(e.id(),p.participantId());
    }
    /** 학생 문장마다 level을 정해 closestCandidate는 첫 후보로 답하는 모의 판정. */
    static Map<String,Judgment> answer(List<Item> items,Function<String,Integer> level) {
        var out=new LinkedHashMap<String,Judgment>();
        for(var item:items) out.put(item.id(),new Judgment(level.apply(item.student()),item.candidates().get(0).id()));
        return out;
    }
    AssessmentModels.Sentence sentence(AssessmentModels.Analysis a,String text){return a.sentences().stream().filter(s->s.text().equals(text)).findFirst().orElseThrow();}

    @SuppressWarnings("unchecked")
    @Test void paraphraseConfirmedByBothModelsCountsAsAi() {
        when(client.judgeWithGemini(any())).thenAnswer(call->answer(call.getArgument(0),s->s.equals(PARAPHRASE)?3:0));
        when(client.judgeWithAnthropic(any())).thenAnswer(call->answer(call.getArgument(0),s->3));
        var f=submit("");
        var before=assessment.analysis(f.id(),f.pid());
        assertThat(before.semanticStatus()).isEqualTo("PENDING");
        assertThat(sentence(before,PARAPHRASE).source()).isEqualTo("DIRECT");
        assertThat(before.contribution().aiReferenced()).isZero();

        job.process(f.id(),f.pid());
        var after=assessment.analysis(f.id(),f.pid());
        assertThat(after.semanticStatus()).isEqualTo("DONE");
        var s=sentence(after,PARAPHRASE);
        assertThat(s.source()).isEqualTo("AI_REFERENCED");assertThat(s.reason()).isEqualTo("SEMANTIC");assertThat(s.needsReview()).isFalse();
        assertThat(s.semantic().geminiLevel()).isEqualTo(3);assertThat(s.semantic().sonnetLevel()).isEqualTo(3);assertThat(s.conversationId()).isNotNull();
        assertThat(sentence(after,OWN).source()).isEqualTo("DIRECT");
        assertThat(after.contribution().aiReferenced()).isPositive();assertThat(after.contribution().direct()+after.contribution().aiReferenced()).isEqualTo(100);
        // Gemini는 두 문장 모두 한 번에, Sonnet은 의심 문장(단계 3)만 받습니다.
        var geminiItems=org.mockito.ArgumentCaptor.forClass(List.class);verify(client,times(1)).judgeWithGemini(geminiItems.capture());
        assertThat((List<Item>)geminiItems.getValue()).extracting(Item::student).containsExactlyInAnyOrder(PARAPHRASE,OWN);
        var sonnetItems=org.mockito.ArgumentCaptor.forClass(List.class);verify(client,times(1)).judgeWithAnthropic(sonnetItems.capture());
        assertThat((List<Item>)sonnetItems.getValue()).extracting(Item::student).containsExactly(PARAPHRASE);

        // 다시 분석해도 저장된 판정을 쓰고 다시 호출하지 않습니다.
        assessment.run(f.id());db.update("UPDATE grading_analyses SET analysis_status='DONE',semantic_status='PENDING' WHERE participant_id=?",f.pid());
        job.process(f.id(),f.pid());
        verify(client,times(1)).judgeWithGemini(any());verify(client,times(1)).judgeWithAnthropic(any());
        assertThat(sentence(assessment.analysis(f.id(),f.pid()),PARAPHRASE).reason()).isEqualTo("SEMANTIC");
    }
    @Test void modelsDisagreeingLeavesSentenceDirectForTeacherReview() {
        when(client.judgeWithGemini(any())).thenAnswer(call->answer(call.getArgument(0),s->s.equals(PARAPHRASE)?3:0));
        when(client.judgeWithAnthropic(any())).thenAnswer(call->answer(call.getArgument(0),s->2));
        var f=submit("");job.process(f.id(),f.pid());
        var a=assessment.analysis(f.id(),f.pid());
        assertThat(a.semanticStatus()).isEqualTo("DONE");
        var s=sentence(a,PARAPHRASE);
        assertThat(s.source()).isEqualTo("DIRECT");assertThat(s.needsReview()).isTrue();assertThat(a.contribution().aiReferenced()).isZero();
    }
    @Test void geminiFailureKeepsStageOneResult() {
        when(client.judgeWithGemini(any())).thenThrow(new JudgeFailure("JUDGE_GEMINI_TIMEOUT"));
        var f=submit("");job.process(f.id(),f.pid());
        var a=assessment.analysis(f.id(),f.pid());
        assertThat(a.semanticStatus()).isEqualTo("UNAVAILABLE");
        assertThat(a.sentences()).allMatch(s->s.reason()==null&&!s.needsReview());
        verify(client,never()).judgeWithAnthropic(any());
    }
    @Test void sonnetFailureMarksGeminiSuspicionsForReviewOnly() {
        when(client.judgeWithGemini(any())).thenAnswer(call->answer(call.getArgument(0),s->s.equals(PARAPHRASE)?3:0));
        when(client.judgeWithAnthropic(any())).thenThrow(new JudgeFailure("JUDGE_ANTHROPIC_HTTP_529"));
        var f=submit("");job.process(f.id(),f.pid());
        var a=assessment.analysis(f.id(),f.pid());
        assertThat(a.semanticStatus()).isEqualTo("PARTIAL");
        assertThat(sentence(a,PARAPHRASE)).matches(s->s.source().equals("DIRECT")&&s.needsReview());
        assertThat(a.contribution().aiReferenced()).isZero();
    }
}
