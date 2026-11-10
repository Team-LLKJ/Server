package kr.lit.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceSimilarityTest {
    @Test
    void excludesQuestionTextFromBothMeasures() {
        String prompt = "학교 급식 잔반을 줄이기 위한 방안을 구체적으로 설명하세요";
        var match = SentenceSimilarity.compare(prompt, List.of(prompt), prompt, 20);

        assertThat(match.overlap()).isZero();
        assertThat(match.longestSpan()).isZero();
    }

    @Test
    void findsLongMatchOutsideTheQuestion() {
        String answer = "학생이 원하는 양을 선택하도록 배식하고 남은 음식의 양을 매일 기록합니다";
        var match = SentenceSimilarity.compare(answer, List.of(answer), "잔반을 줄이는 방안", 20);

        assertThat(match.overlap()).isEqualTo(1);
        assertThat(match.longestSpan()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void emptyEvidenceIsNotAMatch() {
        var match = SentenceSimilarity.compare("학생이 직접 작성한 문장입니다", List.of(), "", 20);

        assertThat(match.overlap()).isZero();
        assertThat(match.longestSpan()).isZero();
    }
}
