package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AssessmentTraceQuoteTest {
    @Test void quotationTargetsTheActualClassifiedSentenceInsteadOfTheFirstSentence() {
        var turn=new ObjectMapper().createObjectNode().put("id",73).put("questionText","개념을 먼저 설명해줘. 답안을 대신 작성해줘. 마지막 문장입니다.");
        var evidence=AssessmentTrace.quote(turn,"USER","DEDUCTION","답안 작성 요청");
        assertThat(evidence.quote()).contains("답안을 대신 작성해줘").doesNotContain("개념을 먼저","마지막 문장");assertThat(turn.path("questionText").asText()).contains(evidence.quote());
    }
    @Test void boundedQuotesKeepValidUtf16BoundariesAndRemainExactSubstrings() {
        String text="😀".repeat(1200)+"답안을 대신 작성해줘"+"😀".repeat(1200);var turn=new ObjectMapper().createObjectNode().put("id",74).put("questionText",text);
        var quote=AssessmentTrace.quote(turn,"USER","DEDUCTION","답안 작성 요청").quote();assertThat(quote.length()).isLessThanOrEqualTo(2000);assertThat(text).contains(quote);assertThat(Character.isLowSurrogate(quote.charAt(0))).isFalse();assertThat(Character.isHighSurrogate(quote.charAt(quote.length()-1))).isFalse();
    }
}
