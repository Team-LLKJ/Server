package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static kr.lit.api.AssessmentModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 답안 출처 판정 V2(LIT 답안 출처 판정 개선안 1단계) 회귀 테스트. 개선안 실험표의 유형을 그대로 옮겼습니다. */
class AnswerContributionV2Test {
    static final String QUESTION = "지방 인구 감소의 원인을 설명하세요.";
    static final String AI = "청년층은 더 많은 취업 기회를 찾아 수도권이나 대도시로 이동하는 경향이 강합니다. "
            + "지방 인구 감소의 원인은 크게 일자리와 교육 기회의 부족입니다. 이로 인해 지방의 고령화가 빠르게 진행되고 있습니다.";

    final ObjectMapper json = new ObjectMapper();
    final AssessmentAnalyzer analyzer = new AssessmentAnalyzer(new GradingConfig());

    ObjectNode source(String answerHtml, boolean copyEvent) {
        var root = json.createObjectNode();
        root.put("aiPolicy", "FULL");
        root.put("evaluationContent", "<p>각 문항에 자신의 생각을 쓰세요.</p>");
        ArrayNode answers = root.putArray("answers");
        var item = answers.addObject();
        item.putObject("question").put("id", "q1").put("position", 1).put("title", "문항 1").put("content", "<p>" + QUESTION + "</p>");
        item.putObject("answer").put("content", answerHtml).put("version", 3);
        var turn = root.putArray("conversations").addObject();
        turn.put("id", 7).put("requestId", "r1").put("questionId", "q1").put("questionText", "지방 인구가 왜 줄어드는지 설명해줘")
                .put("answerText", AI).put("state", "COMPLETED");
        var events = root.putArray("learningEvents");
        if (copyEvent) events.addObject().put("id", 1).put("type", "AI_ANSWER_COPY").put("questionId", "q1").putObject("details");
        return root;
    }

    Analysis analyze(String html) { return analyze(html, false); }
    Analysis analyze(String html, boolean copy) { return analyzer.analyze("p1", source(html, copy), AssessmentAnalyzer.DisclosureInput.EMPTY, Instant.EPOCH); }

    static Sentence only(Analysis a) { assertThat(a.sentences()).hasSize(1); return a.sentences().get(0); }

    @Test void verbatimSentenceIsAiBySpanMatch() {
        var s = only(analyze("<p>청년층은 더 많은 취업 기회를 찾아 수도권이나 대도시로 이동하는 경향이 강합니다.</p>"));
        assertThat(s.source()).isEqualTo("AI");
        assertThat(s.reason()).isEqualTo("SPAN_MATCH");
        assertThat(s.longestSpan()).isGreaterThanOrEqualTo(20);
        assertThat(s.conversationId()).isEqualTo(7L);
    }

    @Test void slightlyEditedSentenceIsAi() {
        var s = only(analyze("<p>청년층은 더 많은 일자리 기회를 찾아 수도권이나 대도시로 이동하는 경향이 크다.</p>"));
        assertThat(s.source()).isEqualTo("AI");
    }

    @Test void fullParaphraseStaysDirectInStageOne() {
        // 개선안 한계: 글자 비교만으로는 바꿔 쓴 문장을 잡지 못합니다(2단계 LLM 판정 대상).
        var s = only(analyze("<p>젊은 사람들은 취직할 곳이 많은 서울 근처나 큰 도시로 떠나는 경우가 많다.</p>"));
        assertThat(s.source()).isEqualTo("DIRECT");
        assertThat(s.reason()).isNull();
        assertThat(s.conversationId()).isNull();
    }

    @Test void quotingTheQuestionDoesNotRaiseSimilarity() {
        var s = only(analyze("<p>지방 인구 감소의 원인을 정리해 보았다.</p>"));
        assertThat(s.source()).isEqualTo("DIRECT");
        // 같은 문장도 문항 문구를 빼지 않으면 AI 답변과 절반 이상 겹칩니다.
        assertThat(AssessmentAnalyzer.containment("지방 인구 감소의 원인을 정리해 보았다.", AI)).isGreaterThanOrEqualTo(0.4);
    }

    @Test void copyEventNoLongerAddsBonusToOwnSentences() {
        var s = only(analyze("<p>우리 동네도 학교가 문을 닫아 아이들이 버스를 타고 멀리 다닌다.</p>", true));
        assertThat(s.source()).isEqualTo("DIRECT");
    }

    @Test void copiedSentenceInsideLongParagraphIsNotDiluted() {
        String own = "우리 동네도 초등학교가 문을 닫아서 아이들이 버스를 타고 옆 마을까지 다닌다. "
                + "할머니는 병원에 가려면 한 시간 넘게 기다려야 한다고 말씀하셨다. 나는 교통과 의료 문제부터 해결해야 한다고 생각한다.";
        var a = analyze("<p>청년층은 더 많은 취업 기회를 찾아 수도권이나 대도시로 이동하는 경향이 강합니다. " + own + "</p>");
        assertThat(a.sentences()).hasSize(4);
        assertThat(a.sentences()).extracting(Sentence::source).containsExactly("AI", "DIRECT", "DIRECT", "DIRECT");
        assertThat(a.paragraphs().get(0).source()).isEqualTo("AI_REFERENCED");
        var c = a.contribution();
        assertThat(c.aiReferenced()).isBetween(20, 40);
        assertThat(c.direct() + c.aiReferenced()).isEqualTo(100);
    }

    @Test void responseContractAndSegmentsCoverTheWholeAnswer() {
        var a = analyze("<p>청년층은 더 많은 취업 기회를 찾아 수도권이나 대도시로 이동하는 경향이 강합니다.  나는 다르게 생각한다.</p><p>둘째 문단이다.</p>");
        var c = a.contribution();
        assertThat(c.basis()).isEqualTo("TEXT_SIMILARITY_ESTIMATE_V2");
        assertThat(c.aiUsed()).isZero();
        assertThat(c.direct() + c.aiReferenced()).isEqualTo(100);
        assertThat(a.paragraphs()).extracting(Paragraph::source).containsOnly("DIRECT", "AI_REFERENCED");
        String text = a.answerTexts().get(0).text();
        long covered = 0;
        int previousEnd = 0;
        for (var segment : c.segments()) {
            assertThat(segment.type()).isIn("STUDENT", "AI_REFERENCED");
            assertThat(segment.answerVersion()).isEqualTo(3);
            if (segment.start() != previousEnd) assertThat(text.substring(previousEnd, segment.start())).isEqualTo("\n");
            covered += segment.end() - segment.start();
            previousEnd = segment.end();
        }
        assertThat(covered).isEqualTo(text.replace("\n", "").length());
        for (var s : a.sentences()) assertThat(text.substring(s.start(), s.end())).isEqualTo(s.text());
        assertThat(a.summary()).contains("직접 작성으로 추정되는 비중은 " + c.direct() + "%").doesNotContain("높은 유사도");
        assertThat(a.definition()).containsEntry("basis", "TEXT_SIMILARITY_ESTIMATE_V2").containsEntry("minSpanLength", 20);
    }

    @Test void emptyAnswerHasZeroContribution() {
        var c = analyze("").contribution();
        assertThat(List.of(c.direct(), c.aiReferenced(), c.aiUsed())).containsOnly(0);
    }

    @Test void splitsSentencesOnTerminalPunctuationOnly() {
        String p = "원주율은 3.14이다. 정말? 그렇다!\"  끝";
        var parts = SentenceSimilarity.sentences(p).stream().map(r -> p.substring(r.start(), r.end())).toList();
        assertThat(parts).containsExactly("원주율은 3.14이다.", "정말?", "그렇다!\"", "끝");
    }
}
