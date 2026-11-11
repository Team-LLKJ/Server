package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static kr.lit.api.PurposeUsageAnalyzer.Purpose.*;
import static org.assertj.core.api.Assertions.*;

/** 질문 용도: 질문 하나에 용도 하나, 비율은 확정+임시 용도로 계산하고 OTHER·UNDECIDED는 비율에서 뺍니다. */
class PurposeUsageV4Test {
    final ObjectMapper json=new ObjectMapper();
    static String turn(long id,String text,String classification){
        return "{\"id\":"+id+",\"questionId\":\"q\",\"questionText\":\""+text+"\",\"state\":\"COMPLETED\""+(classification==null?"":",\"purposeClassification\":"+classification)+"}";
    }
    static String llm(String purpose,String votes,String haiku,boolean review,String method){
        return "{\"status\":\"DONE\",\"purpose\":\""+purpose+"\",\"votes\":["+votes+"],\"haikuPurpose\":"+(haiku==null?"null":"\""+haiku+"\"")+",\"needsReview\":"+review+",\"teacherPurpose\":null,\"method\":\""+method+"\"}";
    }

    @Test void usesStoredClassificationsAndCountsOneQuestionOnce() throws Exception {
        var source=json.readTree("{\"conversations\":["+String.join(",",
                turn(1,"광합성이 뭐야?",llm("UNDERSTAND","\"UNDERSTAND\",\"UNDERSTAND\",\"UNDERSTAND\"",null,false,"LLM_GEMINI_X3")),
                turn(2,"40%는 누가 센 거야?",llm("REVIEW","\"REVIEW\",\"REVIEW\",\"UNDERSTAND\"","REVIEW",true,"LLM_GEMINI_X3_HAIKU")),
                turn(3,"이 문장 고쳐줘",llm("EDIT","\"EDIT\",\"EDIT\",\"EDIT\"",null,false,"LLM_GEMINI_X3")),
                turn(4,"제출 버튼 어디 있어?",llm("OTHER","\"OTHER\",\"OTHER\",\"OTHER\"",null,false,"LLM_GEMINI_X3")),
                turn(5,"애매한 질문",llm("UNDECIDED","\"PLAN\",\"REVIEW\",\"DRAFT\"",null,true,"LLM_GEMINI_X3")))+"]}");
        var view=new PurposeUsageAnalyzer().analyzeUsage(source);
        assertThat(view.status()).isEqualTo("READY");assertThat(view.questionCount()).isEqualTo(5);
        assertThat(view.usage()).extracting(PurposeUsageAnalyzer.Group::purpose).containsExactly(UNDERSTAND,REVIEW,EDIT);
        assertThat(view.usage()).extracting(PurposeUsageAnalyzer.Group::name).containsExactly("이해·조사","비판적 검토","표현 수정");
        assertThat(view.usage().stream().mapToDouble(PurposeUsageAnalyzer.Group::percentage).sum()).isCloseTo(100.0,within(1e-9));
        assertThat(view.usage().get(0).percentage()).isIn(33.3,33.4);
        assertThat(view.reviewCount()).isEqualTo(2);assertThat(view.otherCount()).isEqualTo(1);assertThat(view.undecidedCount()).isEqualTo(1);
        var q2=view.questions().get(1);
        assertThat(q2.votes()).containsExactly("REVIEW","REVIEW","UNDERSTAND");assertThat(q2.haikuPurpose()).isEqualTo("REVIEW");assertThat(q2.needsReview()).isTrue();
        assertThat(q2.purposes()).containsExactly(REVIEW);assertThat(q2.weightPerPurpose()).isEqualTo(1.0);
        assertThat(view.method()).isEqualTo("MIXED");
    }
    @Test void teacherPurposeWinsAndClearsReview() throws Exception {
        var source=json.readTree("{\"conversations\":["+turn(1,"근거는?","{\"status\":\"DONE\",\"purpose\":\"UNDERSTAND\",\"votes\":[\"REVIEW\",\"UNDERSTAND\",\"UNDERSTAND\"],\"teacherPurpose\":\"UNDERSTAND\",\"needsReview\":false,\"method\":\"TEACHER\"}")+"]}");
        var q=new PurposeUsageAnalyzer().analyzeUsage(source).questions().get(0);
        assertThat(q.purpose()).isEqualTo(UNDERSTAND);assertThat(q.teacherPurpose()).isEqualTo("UNDERSTAND");assertThat(q.needsReview()).isFalse();assertThat(q.method()).isEqualTo("TEACHER");
    }
    @Test void fallsBackToSingleKeywordPurposeWhenNotClassified() throws Exception {
        var source=json.readTree("{\"conversations\":["+String.join(",",
                turn(1,"이 문단 이어서 써줘",null),turn(2,"맞춤법 고쳐줘",null),turn(3,"출처가 어디야?",null),turn(4,"서론 구성 어떻게 할까",null),
                turn(5,"개념 설명해줘",null),turn(6,"안녕",null),turn(7,"자료 찾아줘","{\"status\":\"PENDING\",\"votes\":[],\"needsReview\":false}"))+"]}");
        var view=new PurposeUsageAnalyzer().analyzeUsage(source);
        assertThat(view.questions()).extracting(PurposeUsageAnalyzer.Question::purpose).containsExactly(DRAFT,EDIT,REVIEW,PLAN,UNDERSTAND,OTHER,UNDERSTAND);
        assertThat(view.questions()).allMatch(q->q.method().equals("KEYWORD_FALLBACK"));
        assertThat(view.method()).isEqualTo("KEYWORD_FALLBACK");assertThat(view.otherCount()).isEqualTo(1);assertThat(view.pendingCount()).isGreaterThanOrEqualTo(1);
        assertThat(view.limitations()).anyMatch(l->l.contains("KEYWORD_FALLBACK"));
    }
    @Test void distinguishesNoRecordsMissingRecordsAndIncompleteResponse() throws Exception {
        var analyzer=new PurposeUsageAnalyzer();
        assertThat(analyzer.analyzeUsage(json.readTree("{\"conversations\":[]}")).status()).isEqualTo("NO_RECORDS");
        assertThat(analyzer.analyzeUsage(json.createObjectNode()).status()).isEqualTo("RECORDS_UNAVAILABLE");
        var partial=analyzer.analyzeUsage(json.readTree("{\"conversations\":[{\"id\":1,\"questionText\":\"개념\",\"state\":\"FAILED\"}] }"));
        assertThat(partial.status()).isEqualTo("PARTIAL");assertThat(partial.questionCount()).isEqualTo(1);
    }
    @Test void assessmentClassifyUsesSameStoredPurpose() throws Exception {
        var turn=json.readTree(turn(1,"이 문장 고쳐줘",llm("REVIEW","\"REVIEW\",\"REVIEW\",\"REVIEW\"",null,false,"LLM_GEMINI_X3")));
        assertThat(AssessmentAnalyzer.classify(turn)).isEqualTo(AssessmentModels.QuestionType.VERIFICATION);
        assertThat(AssessmentAnalyzer.classify(json.readTree(turn(2,"이 문장 고쳐줘",null)))).isEqualTo(AssessmentAnalyzer.classify("이 문장 고쳐줘"));
        assertThat(PurposeClassifier.majority(java.util.List.of("PLAN","REVIEW","PLAN"))).isEqualTo("PLAN");
        assertThat(PurposeClassifier.majority(java.util.List.of("PLAN","REVIEW","DRAFT"))).isEqualTo("UNDECIDED");
    }
}
