package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 질문 용도(2026-11-14): 과반과 Haiku가 같으면 확정, 문항 내용을 맥락으로 보내고 문항 답을 시키는 질문은 DRAFT 규칙. */
class PurposeClassifierRuleTest {
    final ObjectMapper json=new ObjectMapper();

    String answer(String data,String purpose) throws Exception {
        var items=json.readTree(data.substring(data.indexOf('{'))).path("items");
        var results=new ArrayList<Map<String,Object>>();
        for(var item:items) results.add(Map.of("id",item.path("id").asText(),"purpose",purpose,"reason","r","cues",List.of()));
        return json.writeValueAsString(Map.of("results",results));
    }
    PurposeClassifier.Result classify(List<String> gemini,String haiku) {
        var client=mock(SemanticJudgeClient.class);when(client.anthropicConfigured()).thenReturn(true);
        var n=new AtomicInteger();
        when(client.callGemini(anyString(),anyString(),any(),isNull())).thenAnswer(inv->answer(inv.getArgument(1),gemini.get(n.getAndIncrement())));
        when(client.callAnthropic(anyString(),anyString(),isNull())).thenAnswer(inv->answer(inv.getArgument(1),haiku));
        var classifier=new PurposeClassifier(null,json,client,new SemanticJudgeConfig(),null);
        var item=new PurposeClassifier.Item("t1",1,List.of(),"","우리 지역 변화 한가지 골라서 원인 설명해줘","변화\n우리 지역의 변화 한 가지를 고르고 그 원인을 설명하세요.");
        return classifier.classify(List.of(item)).get("t1");
    }

    @Test void majorityAgreeingWithHaikuIsConfirmed() {
        var r=classify(List.of("DRAFT","DRAFT","PLAN"),"DRAFT");
        assertThat(r.purpose()).isEqualTo("DRAFT");assertThat(r.needsReview()).isFalse();assertThat(r.haikuPurpose()).isEqualTo("DRAFT");
        assertThat(r.method()).isEqualTo(PurposeUsageAnalyzer.METHOD_LLM_HAIKU);
    }
    @Test void haikuDifferentFromMajorityOrNoMajorityNeedsReview() {
        var different=classify(List.of("DRAFT","DRAFT","PLAN"),"PLAN");
        assertThat(different.purpose()).isEqualTo("PLAN");assertThat(different.needsReview()).isTrue();
        var spread=classify(List.of("DRAFT","UNDERSTAND","PLAN"),"DRAFT");
        assertThat(spread.purpose()).isEqualTo("DRAFT");assertThat(spread.needsReview()).isTrue();
    }
    @Test void questionItemIsSentAndTheAnswerTheItemAsksForIsDraft() {
        var client=mock(SemanticJudgeClient.class);
        when(client.callGemini(anyString(),anyString(),any(),isNull())).thenAnswer(inv->answer(inv.getArgument(1),"DRAFT"));
        var classifier=new PurposeClassifier(null,json,client,new SemanticJudgeConfig(),null);
        classifier.classify(List.of(new PurposeClassifier.Item("t1",1,List.of(),"","우리 지역 변화 한가지 골라서 원인 설명해줘","변화\n우리 지역의 변화 한 가지를 고르고 그 원인을 설명하세요.")));
        var data=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client,times(3)).callGemini(eq(PurposeClassifier.INSTRUCTIONS),data.capture(),any(),isNull());
        assertThat(data.getValue()).contains("\"item\":\"변화\\n우리 지역의 변화 한 가지를 고르고 그 원인을 설명하세요.\"");
        assertThat(PurposeClassifier.INSTRUCTIONS).contains("item(질문한 문항)이 학생에게 요구하는 답 자체를 AI에게 시키면","우리 지역 변화 한가지 골라서 원인 설명해줘\" → DRAFT");
        assertThat(PurposeClassifier.PROMPT_VERSION).isEqualTo("PURPOSE_GEMINI_X3_V4");
        assertThat(PurposeClassifier.item("변화","가".repeat(700))).hasSize(3+600);
    }
}
