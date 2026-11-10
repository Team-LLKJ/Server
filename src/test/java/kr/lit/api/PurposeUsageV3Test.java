package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PurposeUsageV3Test {
    final ObjectMapper json=new ObjectMapper();
    @Test void dividesOneQuestionsWeightAcrossPurposesAndPreservesTotal()throws Exception {
        var source=json.readTree("{\"conversations\":[{\"id\":1,\"questionId\":\"q\",\"questionText\":\"개념 설명과 자료 검색\",\"state\":\"COMPLETED\"},{\"id\":2,\"questionId\":\"q\",\"questionText\":\"안녕\",\"state\":\"COMPLETED\"}]}");
        var result=new PurposeUsageAnalyzer().analyzeUsage(source);assertThat(result.status()).isEqualTo("READY");assertThat(result.questions().get(0).weightPerPurpose()).isEqualTo(0.5);assertThat(result.usage().stream().mapToDouble(PurposeUsageAnalyzer.Group::percentage).sum()).isEqualTo(100);
        assertThat(result.usage().stream().filter(g->g.purpose()==PurposeUsageAnalyzer.Purpose.OTHER).findFirst().orElseThrow().percentage()).isEqualTo(50);
        assertThat(result.usage().get(0).representativeQuestions().get(0).conversationId()).isPositive();
    }
    @Test void distinguishesNoRecordsMissingRecordsAndIncompleteResponse()throws Exception {
        var analyzer=new PurposeUsageAnalyzer();assertThat(analyzer.analyzeUsage(json.readTree("{\"conversations\":[]}")).status()).isEqualTo("NO_RECORDS");assertThat(analyzer.analyzeUsage(json.createObjectNode()).status()).isEqualTo("RECORDS_UNAVAILABLE");
        var partial=analyzer.analyzeUsage(json.readTree("{\"conversations\":[{\"id\":1,\"questionText\":\"개념\",\"state\":\"FAILED\"}] }"));assertThat(partial.status()).isEqualTo("PARTIAL");assertThat(partial.questionCount()).isEqualTo(1);
    }
}
