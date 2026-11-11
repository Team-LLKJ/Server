package kr.lit.api;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:immediate-submission;DB_CLOSE_DELAY=-1",
    "lit.grading.immediate-submission-analysis=true"
})
class SubmissionAnalysisApiTest extends AuthTestSupport {
    @Test
    void analyzesCommittedSubmissionWithPeriodicWorkersIdle() {
        var evaluation = evaluations.create("제출 직후 분석", 60);
        var config = work.configure(evaluation.id(), new Configure(AiPolicy.FULL,
            List.of(new QuestionInput(1, "문항", "관찰한 내용을 설명하세요."))));
        var student = evaluations.join(evaluation.pin(), "학생", null);
        evaluations.start(evaluation.id());
        work.save(evaluation.id(), student.participantToken(), config.questions().get(0).id(),
            new SaveAnswer("관찰 결과를 정리했습니다.", 0, AnswerSource.DIRECT, null));

        var review = work.review(evaluation.id(), student.participantToken());
        work.submit(evaluation.id(), student.participantToken(), review.reviewToken());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(db.queryForObject("SELECT analysis_status FROM grading_analyses WHERE participant_id=?",
                String.class, student.participantId())).isEqualTo("DONE");
            assertThat(db.queryForObject("SELECT status FROM process_grading_jobs WHERE participant_id=?",
                String.class, student.participantId())).isEqualTo("HOLD");
        });
    }
}
