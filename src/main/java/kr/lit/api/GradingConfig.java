package kr.lit.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 채점 알고리즘(7-4)의 가중치·경계값. 코드에 고정하지 않고 application.yml의 lit.grading.* 또는
 * 환경변수(LIT_GRADING_...)로 조정합니다. 실제 교사 채점과 비교해 보정하는 용도입니다.
 */
@Component
@ConfigurationProperties(prefix="lit.grading")
@org.springframework.validation.annotation.Validated
public class GradingConfig {
    /** 문단 유사도(AI 응답 대비 3-gram 포함률)가 이 값 이상이면 AI_USED. */
    @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0")
    private double aiUsedThreshold=0.8;
    /** 이 값 이상이면 AI_REFERENCED, 미만이면 DIRECT. */
    @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0")
    private double aiReferencedThreshold=0.4;
    /** 같은 문항에 AI 답변 복사(AI_ANSWER_COPY) 기록이 있을 때 유사도에 더하는 값. */
    @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0")
    private double copyEventBonus=0.15;
    /** ① 목적 적절성: 답안 대리 작성 요청 비율에 곱하는 감점 가중치. */
    @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("10.0")
    private double draftingPenalty=1.0;
    /** ① 목적 적절성에서 적절한 유형으로 보는 질문 유형. */
    @jakarta.validation.constraints.NotEmpty
    private List<@jakarta.validation.constraints.Pattern(regexp="CONCEPT_UNDERSTANDING|RESEARCH|IDEA_EXPLORATION|ANSWER_DRAFTING|VERIFICATION|OTHER") String> appropriateTypes=new ArrayList<>(List.of("CONCEPT_UNDERSTANDING","RESEARCH","IDEA_EXPLORATION"));
    /** ② 자기 주도: 추가 질문을 '구체적'으로 보는 최소 글자 수. */
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(4000)
    private int specificQuestionMinLength=20;
    /** 사용 방법별 정도: 비중이 이 값 이상이면 HIGH, 다음 값 이상이면 MEDIUM, 그 외 LOW. */
    private double usageHighShare=0.5;
    private double usageMediumShare=0.2;
    /** AI 금지(Level 1) 평가에서 확인 대상으로 표시할 붙여넣기 길이(글자). */
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(10000000)
    private int longPasteLength=200;
    /** AI 허용 단계별 AI_USED(그대로 사용) 문단 비율 상한. 초과 시 Level 1~2는 VIOLATION, 3~5는 CHECK. */
    private Map<Integer,Double> maxAiUsedShare=new HashMap<>(Map.of(1,0.0,2,0.0,3,0.3,4,0.7,5,1.0));

    @jakarta.validation.constraints.AssertTrue(message="유사도 경계는 0<=referenced<used<=1이어야 합니다.")
    public boolean isThresholdOrderValid() {return aiReferencedThreshold>=0&&aiReferencedThreshold<aiUsedThreshold&&aiUsedThreshold<=1;}
    @jakarta.validation.constraints.AssertTrue(message="사용 비중 경계는 0<=medium<high<=1이어야 합니다.")
    public boolean isUsageOrderValid() {return usageMediumShare>=0&&usageMediumShare<usageHighShare&&usageHighShare<=1;}
    @jakarta.validation.constraints.AssertTrue(message="AI 단계별 상한은1~5단계의 유한한0~1 값이어야 합니다.")
    public boolean isPolicySharesValid() {return maxAiUsedShare!=null&&maxAiUsedShare.keySet().equals(Set.of(1,2,3,4,5))&&maxAiUsedShare.values().stream().allMatch(v->v!=null&&Double.isFinite(v)&&v>=0&&v<=1);}
    public double getAiUsedThreshold() { return aiUsedThreshold; }
    public void setAiUsedThreshold(double v) { aiUsedThreshold=v; }
    public double getAiReferencedThreshold() { return aiReferencedThreshold; }
    public void setAiReferencedThreshold(double v) { aiReferencedThreshold=v; }
    public double getCopyEventBonus() { return copyEventBonus; }
    public void setCopyEventBonus(double v) { copyEventBonus=v; }
    public double getDraftingPenalty() { return draftingPenalty; }
    public void setDraftingPenalty(double v) { draftingPenalty=v; }
    public List<String> getAppropriateTypes() { return appropriateTypes; }
    public void setAppropriateTypes(List<String> v) { appropriateTypes=v; }
    public int getSpecificQuestionMinLength() { return specificQuestionMinLength; }
    public void setSpecificQuestionMinLength(int v) { specificQuestionMinLength=v; }
    public double getUsageHighShare() { return usageHighShare; }
    public void setUsageHighShare(double v) { usageHighShare=v; }
    public double getUsageMediumShare() { return usageMediumShare; }
    public void setUsageMediumShare(double v) { usageMediumShare=v; }
    public int getLongPasteLength() { return longPasteLength; }
    public void setLongPasteLength(int v) { longPasteLength=v; }
    public Map<Integer,Double> getMaxAiUsedShare() { return maxAiUsedShare; }
    public void setMaxAiUsedShare(Map<Integer,Double> v) { maxAiUsedShare=v; }
}
