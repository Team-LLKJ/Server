package kr.lit.api;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "LIT_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
@TestPropertySource(properties = "lit.grading.immediate-submission-analysis=true")
class PostgresSubmissionApiTest extends AuthTestSupport {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("LIT_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("LIT_TEST_POSTGRES_USER", "lit"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("LIT_TEST_POSTGRES_PASSWORD", ""));
    }

    @Test
    void savesAndUpdatesAnswersThenAnalyzesAndExportsSubmission() throws Exception {
        var evaluation = evaluations.create("PostgreSQL 평가", 60);
        var config = work.configure(evaluation.id(), new Configure(AiPolicy.DISABLED,
            List.of(new QuestionInput(1, "문항", "관찰한 결과를 설명하세요."))));
        var student = evaluations.join(evaluation.pin(), "학생", null);
        evaluations.start(evaluation.id());
        String questionId = config.questions().get(0).id();
        String token = student.participantToken();
        work.save(evaluation.id(), token, questionId, new SaveAnswer("초안", 0, AnswerSource.DIRECT, null));
        work.save(evaluation.id(), token, questionId, new SaveAnswer("한글 최종 답안", 1, AnswerSource.DIRECT, null));
        var preview = work.review(evaluation.id(), token);
        var submission = work.submit(evaluation.id(), token, preview.reviewToken());
        assertThat(submission.snapshot().at("/answers/0/answer/content").asText()).isEqualTo("한글 최종 답안");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(db.queryForObject("SELECT analysis_status FROM grading_analyses WHERE participant_id=?",
                String.class, student.participantId())).isEqualTo("DONE"));

        evaluations.end(evaluation.id());
        String path = "/api/teacher/evaluations/" + evaluation.id() + "/students/" + student.participantId() + "/report";
        mvc.perform(request(get(path + ".pdf"), teacher)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(request(get(path + "/hwp"), teacher)).andExpect(status().isOk()).andExpect(content().contentType("application/x-hwp"));
    }
}
