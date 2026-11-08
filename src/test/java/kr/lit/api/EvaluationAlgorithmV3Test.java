package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;
import static org.assertj.core.api.Assertions.*;

class EvaluationAlgorithmV3Test {
    final ProcessGradingEngine engine=new ProcessGradingEngine();
    Input input(){var old=ProcessGradingEngineTest.input(1);var p=old.policy();return new Input(old.evaluationId(),old.participantId(),old.submissionHash(),old.title(),old.aiPolicy(),new Policy(p.version(),RUBRIC_VERSION,p.taskGoal(),p.allowedRules(),p.noAiPolicy(),p.questionWeights()),old.questionIds(),old.sources(),old.limitations());}
    AiOutput output(Input in){return V3GradingTestData.explain(in,new ProcessGradingEngineTest().output(in,4,4,4,4,4));}
    AiOutput replace(AiOutput out,int index,Recommendation r){var list=new ArrayList<>(out.recommendations());list.set(index,r);return new AiOutput(out.compliance(),list,out.summary());}
    @Test void validatesNarrativesAndCalculatesScoresWithoutArbitraryDeductions(){
        var in=input();var result=engine.evaluate(in,output(in));assertThat(result.total()).isEqualByComparingTo("75.00");
        assertThat(result.summary()).isEqualTo(V3GradingTestData.SUMMARY);assertThat(result.partialScore().assessedMaxScore()).isEqualByComparingTo("100");
        var citation=result.cells().get(0).details().credits().get(0).evidence().get(0);assertThat(citation.location().questionId()).isEqualTo("q1");assertThat(citation.start()).isNotNull();
        assertThat(result.compliance().status()).isEqualTo("CHECK");
    }
    @Test void insufficientExplanationHoldsCriterionAndPreservesPartialDenominator(){
        var in=input();var out=output(in);var r=out.recommendations().get(0);
        var bad=new Recommendation(r.questionId(),r.criterion(),r.level(),"자기 생각 부족",r.unmetNextLevel(),r.conditions(),r.counterEvidence(),r.limitations(),r.details());
        var result=engine.evaluate(in,replace(out,0,bad));assertThat(result.total()).isNull();assertThat(result.cells().get(0).level()).isNull();
        assertThat(result.partialScore().assessedScore()).isEqualByComparingTo("63.75");assertThat(result.partialScore().assessedMaxScore()).isEqualByComparingTo("85");assertThat(result.partialScore().heldMaxScore()).isEqualByComparingTo("15");
    }
    @Test void essentialUnknownAndContradictoryFindingsNeverBecomeLowScores(){
        var in=input();var out=output(in);var r=out.recommendations().get(0);var d=r.details();
        for(String status:List.of("UNKNOWN","UNMET")){
            var findings=List.of(new ConditionFinding("PURPOSE_L4",status,true,"핵심 판단을 확인할 추가 자료가 필요합니다.",status.equals("UNKNOWN")?List.of():r.conditions().get(0).evidence()));
            var details=new Details(d.credits(),d.deductions(),d.confirmationNeeded(),d.improvements(),findings);
            var bad=new Recommendation(r.questionId(),r.criterion(),4,r.reason(),r.unmetNextLevel(),r.conditions(),r.counterEvidence(),r.limitations(),details);
            var result=engine.evaluate(in,replace(out,0,bad));assertThat(result.cells().get(0).status()).isEqualTo("HOLD");assertThat(result.cells().get(0).score()).isNull();
        }
    }
    @Test void preselectedExclusionIsDistinctAndIsNotRescaledToOneHundred(){
        var old=input();var p=old.policy();var in=new Input(old.evaluationId(),old.participantId(),old.submissionHash(),old.title(),old.aiPolicy(),new Policy(1,RUBRIC_VERSION,p.taskGoal(),p.allowedRules(),p.noAiPolicy(),p.questionWeights(),List.of(Criterion.CONTRIBUTION)),old.questionIds(),old.sources(),old.limitations());
        var out=output(in);var result=engine.evaluate(in,new AiOutput(out.compliance(),out.recommendations().stream().filter(r->r.criterion()!=Criterion.CONTRIBUTION).toList(),out.summary()));
        assertThat(result.total()).isEqualByComparingTo("56.25");assertThat(result.partialScore().assessedMaxScore()).isEqualByComparingTo("75");assertThat(result.partialScore().notApplicableMaxScore()).isEqualByComparingTo("25");
        assertThat(result.cells().get(3).status()).isEqualTo("NOT_APPLICABLE");
        assertThatThrownBy(()->engine.applyReview(in,result,List.of(new LevelOverride("q1",Criterion.CONTRIBUTION,5,"사후 추가")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void repeatedQuoteRequiresExplicitPositionAndRejectsSurrogateSplit(){
        var old=input();var records=new ArrayList<>(old.sources());records.add(new Source("repeat","q1","FINAL_ANSWER","반복 반복 😀",null,7,null,null,10));
        var in=new Input(old.evaluationId(),old.participantId(),old.submissionHash(),old.title(),old.aiPolicy(),old.policy(),old.questionIds(),records,old.limitations());var validator=new GradingEvidenceValidator();
        assertThatThrownBy(()->validator.resolveCitations(in,"q1",List.of(new Citation("repeat","반복")))).hasMessage("AMBIGUOUS_CITATION");
        var quote=validator.resolveCitations(in,"q1",List.of(new Citation("repeat","반복",3,5,null))).get(0);assertThat(quote.location().start()).isEqualTo(13);assertThat(quote.location().answerVersion()).isEqualTo(7);
        assertThatThrownBy(()->validator.resolveCitations(in,"q1",List.of(new Citation("repeat","\uD83D",6,7,null)))).hasMessage("INVALID_CITATION_RANGE");
    }
    @Test void invalidSummaryIsHeldAndHistoricalVersionAcceptsHistoricalFormat(){
        var in=input();var out=output(in);assertThat(engine.evaluate(in,new AiOutput(out.compliance(),out.recommendations(),"질문 6개 중 0개")).status()).isEqualTo("HOLD");
        var historical=ProcessGradingEngineTest.input(1);assertThat(engine.evaluate(historical,new ProcessGradingEngineTest().output(historical,4,4,4,4,4)).total()).isEqualByComparingTo("75");
    }
}
