package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;
import static org.assertj.core.api.Assertions.*;

class ProcessGradingEngineTest {
    final ProcessGradingEngine engine=new ProcessGradingEngine();
    static Input input(int questionCount) {
        var ids=new ArrayList<String>();var weights=new ArrayList<QuestionWeight>();var sources=new ArrayList<Source>();
        for(int i=1;i<=questionCount;i++) {
            String q="q"+i;ids.add(q);weights.add(new QuestionWeight(q,new BigDecimal(questionCount==1?"100":"50")));
            sources.add(new Source("question:"+q,q,"STUDENT_QUESTION","학생이 구체적인 조건과 판단 이유를 설명했습니다."));
            sources.add(new Source("answer:"+q,q,"FINAL_ANSWER","학생의 판단을 최종 답안에 반영했습니다."));
        }
        sources.add(new Source("disclosure",GLOBAL,"DISCLOSURE","선택한 방법과 제외한 이유를 기록했습니다."));
        return new Input("evaluation","participant","hash","과제","TASK_ASSIST",new Policy(1,PREVIOUS_RUBRIC_VERSION,"과제 목표","생성 허용",NoAiPolicy.HOLD,weights),ids,sources,List.of());
    }
    AiOutput output(Input input,int... levels) {
        var recommendations=new ArrayList<Recommendation>();
        // 투명성은 2026-11-14 삭제(levels[4]는 이전 모델 답처럼 들어와도 무시되는지 확인용)
        for(String q:input.questionIds())for(var c:Criterion.active())recommendations.add(recommend(q,c,levels[c.ordinal()]));
        if(levels.length>4)recommendations.add(recommend(GLOBAL,Criterion.TRANSPARENCY,levels[4]));
        return new AiOutput(new Compliance("CHECK","교사 검토",List.of(new Citation("question:q1","학생이 구체적인")),List.of()),recommendations);
    }
    Recommendation recommend(String q,Criterion c,int level) {
        var citations=GLOBAL.equals(q)?List.of(new Citation("disclosure","선택한 방법")):List.of(new Citation("question:"+q,"학생이 구체적인"),new Citation("answer:"+q,"최종 답안"));
        return new Recommendation(q,c,level,"충족 행동 확인","다음 단계 근거 부족",engine.requiredConditions(c,level).stream().map(k->new Condition(k,citations)).toList(),List.of(),List.of());
    }
    @Test void calculatesSeventyExactlyAndDoesNotDeductForCompliance() {
        var in=input(1);var result=engine.evaluate(in,output(in,4,4,3,4,4));
        assertThat(result.total()).isEqualByComparingTo("70.00");assertThat(result.compliance().status()).isEqualTo("CHECK");
        assertThat(result.cells().get(1).score()).isEqualByComparingTo("22.5");
        assertThat(engine.evaluate(in,output(in,4,4,4,4,4)).total()).isEqualByComparingTo("75.00");
    }
    @Test void appliesQuestionWeightsWithoutTransparency() {
        var in=input(2);var result=engine.evaluate(in,output(in,5,5,5,5,5));
        assertThat(result.cells()).hasSize(8);assertThat(result.total()).isEqualByComparingTo("100.00");
    }
    @Test void fabricatedQuotesForeignQuestionAndMissingConditionsHoldTotalRatherThanZero() {
        var in=input(2);var valid=output(in,4,4,4,4,4);
        for(var conditions:List.of(List.<Condition>of(),List.of(new Condition("PURPOSE_L4",List.of(new Citation("question:q2","학생이 구체적인")))),List.of(new Condition("PURPOSE_L4",List.of(new Citation("question:q1","존재하지 않는 인용")))))) {
            var records=new ArrayList<>(valid.recommendations());records.set(0,new Recommendation("q1",Criterion.PURPOSE,4,"설명","다음 단계 불충족",conditions,List.of(),List.of()));
            var result=engine.evaluate(in,new AiOutput(valid.compliance(),records));
            assertThat(result.status()).isEqualTo("HOLD");assertThat(result.total()).isNull();assertThat(result.cells().get(0).level()).isNull();
        }
    }
    @Test void zeroRequiresPositiveEvidenceAndTeacherCanCompleteHeldAreas() {
        var in=input(1);var valid=output(in,1,1,1,1,1);
        assertThat(engine.evaluate(in,valid).total()).isEqualByComparingTo("0.00");
        var held=engine.hold(in,"누락");assertThat(held.total()).isNull();
        var overrides=Criterion.active().stream().map(c->new LevelOverride("q1",c,4,"교사가 행동을 확인함")).toList();
        assertThat(engine.applyReview(in,held,overrides).total()).isEqualByComparingTo("75.00");
    }
    @Test void teacherCanOverrideAiScoreWithDirectScore() {
        var in=input(1);var ai=engine.evaluate(in,output(in,4,4,4,4,4));assertThat(ai.total()).isEqualByComparingTo("75.00");
        var reviewed=engine.applyReview(in,ai,List.of(LevelOverride.ofScore("q1",Criterion.CONTRIBUTION,new java.math.BigDecimal("30"),"핵심 판단을 직접 확인함")));
        var cell=reviewed.cells().stream().filter(c->c.criterion()==Criterion.CONTRIBUTION).findFirst().orElseThrow();
        assertThat(cell.level()).isNull();assertThat(cell.score()).isEqualByComparingTo("30.00");assertThat(cell.status()).isEqualTo("TEACHER_REVIEWED");assertThat(cell.reason()).isEqualTo("핵심 판단을 직접 확인함");
        assertThat(reviewed.total()).isEqualByComparingTo("82.50");
        var held=engine.hold(in,"누락");
        var all=Criterion.active().stream().map(c->LevelOverride.ofScore("q1",c,java.math.BigDecimal.valueOf(c.maxScore()),"만점 확인")).toList();
        assertThat(engine.applyReview(in,held,all).total()).isEqualByComparingTo("100.00");
    }
    @Test void rejectsInvalidDirectScores() {
        var in=input(1);var ai=engine.evaluate(in,output(in,4,4,4,4,4));
        for(var bad:List.of(LevelOverride.ofScore("q1",Criterion.PURPOSE,new java.math.BigDecimal("20.01"),"만점 초과"),
                LevelOverride.ofScore("q1",Criterion.PURPOSE,new java.math.BigDecimal("-1"),"음수"),
                LevelOverride.ofScore("q1",Criterion.PURPOSE,new java.math.BigDecimal("3.333"),"소수 셋째 자리"),
                new LevelOverride("q1",Criterion.PURPOSE,4,"수준과 점수 동시",java.math.BigDecimal.TEN),
                new LevelOverride("q1",Criterion.PURPOSE,null,"둘 다 없음",null)))
            assertThatThrownBy(()->engine.applyReview(in,ai,List.of(bad))).hasMessage("INVALID_OVERRIDE");
    }
    @Test void rejectsDuplicateCriteriaAndKeepsCounterEvidenceAndLimits() {
        var in=input(1);var output=output(in,4,4,4,4,4);var duplicate=new ArrayList<>(output.recommendations());duplicate.add(duplicate.get(0));
        assertThatThrownBy(()->engine.evaluate(in,new AiOutput(output.compliance(),duplicate))).isInstanceOf(IllegalArgumentException.class);
        var record=output.recommendations().get(0);var records=new ArrayList<>(output.recommendations());
        records.set(0,new Recommendation(record.questionId(),record.criterion(),record.level(),record.reason(),record.unmetNextLevel(),record.conditions(),List.of(new Citation("question:q1","판단 이유")),List.of("외부 확인 불가")));
        var result=engine.evaluate(in,new AiOutput(output.compliance(),records));assertThat(result.cells().get(0).counterEvidence()).hasSize(1);assertThat(result.cells().get(0).limitations()).contains("외부 확인 불가");
    }
    @Test void v2UsesOneToFiveWhileHistoricalV1ReviewsKeepZeroToFour() {
        var in=input(1);
        assertThat(engine.evaluate(in,output(in,1,1,1,1,1)).total()).isEqualByComparingTo("0");
        assertThat(engine.evaluate(in,output(in,5,5,5,5,5)).total()).isEqualByComparingTo("100");
        assertThat(engine.evaluate(in,output(in,0,1,1,1,1)).cells().get(0).status()).isEqualTo("HOLD");
        assertThatThrownBy(()->engine.applyReview(in,engine.hold(in,"不足"),List.of(new LevelOverride("q1",Criterion.PURPOSE,0,"invalid")))).hasMessage("INVALID_OVERRIDE");
        var oldPolicy=new Policy(1,LEGACY_RUBRIC_VERSION,"goal","rules",NoAiPolicy.HOLD,in.policy().questionWeights());
        var old=new Input(in.evaluationId(),in.participantId(),in.submissionHash(),in.title(),in.aiPolicy(),oldPolicy,in.questionIds(),in.sources(),in.limitations());
        var overrides=Criterion.active().stream().map(c->new LevelOverride("q1",c,3,"기존 기록 검토")).toList();
        assertThat(engine.applyReview(old,engine.hold(old,"legacy"),overrides).total()).isEqualByComparingTo("75");
        assertThatThrownBy(()->engine.applyReview(old,engine.hold(old,"legacy"),List.of(new LevelOverride("q1",Criterion.PURPOSE,5,"invalid")))).hasMessage("INVALID_OVERRIDE");
    }
}
