package kr.lit.api;

import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;

final class V3GradingTestData {
    static final String SUMMARY="학생은 과제의 해결 방향을 최종 답안에 제시했습니다. 대화에서는 해결 방안을 검토하는 과정을 확인했습니다. 학생의 판단을 답안에 반영한 근거를 확인했습니다. 추가 자료의 정확성은 교사의 확인이 필요합니다.";
    static final String REASON="학생은 과제 조건을 바탕으로 해결 방안을 제시했습니다. 인용한 기록에서 자신의 판단을 설명한 행동을 확인했습니다. 이 행동을 해당 단계의 충족 근거로 판단했습니다.";
    static AiOutput explain(Input input,AiOutput output){
        if(input.policy()==null||!RUBRIC_VERSION.equals(input.policy().rubricVersion()))return output;
        var recommendations=output.recommendations().stream().map(r->{
            if(r.level()==null)return r;
            var references=r.conditions().stream().flatMap(c->c.evidence().stream()).distinct().toList();
            var findings=r.conditions().stream().map(c->new ConditionFinding(c.key(),"MET",true,"해당 행동의 원문을 확인했습니다.",c.evidence())).toList();
            var detail=new Details(List.of(new BehaviorReason("학생의 판단을 확인했습니다.","인용한 기록에서 과제의 조건과 선택 이유를 확인했습니다. 자신의 판단을 제시한 행동을 해당 기준의 충족 근거로 판단했습니다.",references)),List.of(),List.of("별도 참고 자료의 원문은 확보하지 못했습니다."),List.of("선택한 방안의 한계를 추가로 설명하세요."),findings);
            return new Recommendation(r.questionId(),r.criterion(),r.level(),REASON,r.unmetNextLevel(),r.conditions(),r.counterEvidence(),r.limitations(),detail);
        }).toList();
        return new AiOutput(output.compliance(),recommendations,SUMMARY);
    }
}
