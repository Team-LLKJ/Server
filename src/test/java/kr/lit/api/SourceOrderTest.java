package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static kr.lit.api.AssessmentModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 전달 문서 3의 9·10번: 출처는 대화에서 처음 나온 사람(AI가 학생 질문을 되풀이한 내용은 학생 것). */
class SourceOrderTest {
    final ObjectMapper json = new ObjectMapper();
    final AssessmentAnalyzer analyzer = new AssessmentAnalyzer(new GradingConfig());

    ObjectNode source(String answer, String... turns) {
        var root = json.createObjectNode();
        root.put("aiPolicy", "FULL");
        root.put("evaluationContent", "<p>각 문항에 자신의 생각을 쓰세요.</p>");
        var item = root.putArray("answers").addObject();
        item.putObject("question").put("id", "q1").put("position", 1).put("title", "문항 1").put("content", "<p>지역 축제가 줄어든 원인을 설명하세요.</p>");
        item.putObject("answer").put("content", "<p>" + answer + "</p>").put("version", 1);
        var conversations = root.putArray("conversations");
        for (int i = 0; i < turns.length; i += 2)
            conversations.addObject().put("id", 10 + i).put("requestId", "r" + i).put("questionId", "q1")
                    .put("questionText", turns[i]).put("answerText", turns[i + 1]).put("state", "COMPLETED");
        root.putArray("learningEvents");
        return root;
    }
    Sentence only(ObjectNode source) {
        var a = analyzer.analyze("p1", source, AssessmentAnalyzer.DisclosureInput.EMPTY, Instant.EPOCH);
        assertThat(a.sentences()).hasSize(1);
        return a.sentences().get(0);
    }

    @Test void aiEchoOfTheStudentsOwnIdeaStaysDirect() {
        // 운영 사례: 학생이 먼저 낸 구분을 AI가 되풀이 → 학생 답안 문장은 학생 것
        var s = only(source("나는 원인을 경제적 원인과 생활 변화 원인으로 나눠 생각했다.",
                "원인을 경제적 원인과 생활 변화 원인으로 나눠서 쓰려는데 괜찮을까?",
                "원인을 '경제적 원인'과 '생활 변화 원인'으로 나누어 구성하려는 아이디어는 정말 훌륭합니다. 각 원인마다 사례를 하나씩 들어 보세요."));
        assertThat(s.source()).isEqualTo("DIRECT");
        assertThat(s.reason()).isNull();
    }

    @Test void expressionTheAiSaidFirstStaysAiEvenIfTheStudentRepeatsIt() {
        // AI 답변에 먼저 나온 표현을 학생이 다음 질문에 옮겨도 그 표현은 AI 것입니다.
        var s = only(source("축제 예산이 줄고 관광객 소비 패턴이 바뀐 것이 가장 큰 원인이다.",
                "축제가 줄어든 원인을 알려줘",
                "축제 예산이 줄고 관광객 소비 패턴이 바뀐 것이 가장 큰 원인입니다.",
                "축제 예산이 줄고 관광객 소비 패턴이 바뀐 것이 가장 큰 원인이라는 게 맞아?",
                "네, 여러 조사에서 비슷한 결과가 나옵니다."));
        assertThat(s.source()).isEqualTo("AI");
        assertThat(s.conversationId()).isEqualTo(10L);
    }

    @Test void studentOriginKeepsOnlyWhatTheStudentSaidFirst() {
        var origins = SentenceSimilarity.StudentOrigin.chronological(
                List.of("경제적 원인을 먼저 쓸래", "생활 변화도 넣을까"),
                java.util.Arrays.asList("경제적 원인을 먼저 쓰면 좋습니다. 생활 변화도 중요합니다.", null), 20);
        assertThat(origins.get(0).containsGram(AssessmentAnalyzer.normalize("경제적").substring(0, 3))).isTrue();
        // 두 번째 질문의 '생활 변화'는 앞선 AI 답변에 먼저 나왔으므로 학생 것이 아닙니다.
        assertThat(origins.get(1).containsGram(AssessmentAnalyzer.normalize("생활변").substring(0, 3))).isFalse();
    }

    @Test void longPasteRangeMarksDirectSentencesForReview() {
        var root = source("외부에서 정리한 문장을 그대로 옮겼다. 마지막 문장은 직접 썼다.", "아무거나", "답변입니다.");
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("learningEvents")).addObject().put("id", 1).put("type", "PASTE").put("questionId", "q1")
                .putObject("details").put("start", 0).put("end", 19).put("answerVersion", 1).put("length", 300);
        var a = analyzer.analyze("p1", root, AssessmentAnalyzer.DisclosureInput.EMPTY, Instant.EPOCH);
        assertThat(a.sentences()).extracting(Sentence::pasted).containsExactly(true, false);
        assertThat(a.sentences()).extracting(Sentence::needsReview).containsExactly(true, false);
        assertThat(a.reviewCount()).isEqualTo(1);
    }

    @Test void refusalIsDetected() {
        assertThat(PurposeUsageAnalyzer.isRefusal("답안을 대신 써 줄 수는 없어요. 대신 힌트를 드릴게요.")).isTrue();
        assertThat(PurposeUsageAnalyzer.isRefusal("죄송하지만 작성해 드릴 수 없습니다. 스스로 작성해 보세요.")).isTrue();
        assertThat(PurposeUsageAnalyzer.isRefusal("지역 축제는 지역 경제를 살리는 역할을 합니다. 첫째, 관광객이 늘어납니다.")).isFalse();
        assertThat(PurposeUsageAnalyzer.isRefusal("")).isTrue();
    }

    @Test void automaticDisclosureUsesSentenceJudgmentsWhenGiven() {
        var root = source("나는 원인을 경제적 원인과 생활 변화 원인으로 나눠 생각했다.",
                "원인을 경제적 원인과 생활 변화 원인으로 나눠서 쓰려는데 괜찮을까?", "좋은 생각입니다.");
        var analyzer = new AutomaticDisclosureAnalyzer();
        var before = analyzer.generateDisclosure("e", "p", root, Instant.EPOCH);
        assertThat(before.usedParts()).doesNotContain("최종 답안 문단");
        var after = analyzer.generateDisclosure("e", "p", root, Instant.EPOCH, java.util.Map.of(10L, List.of(1)));
        assertThat(after.usedParts()).contains("최종 답안 문단 [1]에 이 대화의 AI 답변을 쓴 문장");
        assertThat(after.evidence().get(0).matchingAnswerParagraphs()).containsExactly(1);
    }

    @Test void promptsCarryTheFirstSpeakerRule() {
        assertThat(SemanticJudgeClient.INSTRUCTIONS).contains("처음 나온 사람", "학생 질문의 내용", "studentQuestions");
        assertThat(PurposeClassifier.INSTRUCTIONS).contains("REVIEW vs EDIT", "내 답안 검토해줘", "그럼 그걸로 써줘");
        var item = new SemanticJudgeClient.Item("s1", "문항", "학생 문장", List.of(), List.of("내 생각은 이래"));
        assertThat(item.studentQuestions()).containsExactly("내 생각은 이래");
    }
}
