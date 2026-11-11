package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 평가 종료 전 채점 결과 비공개, 반 진행률(gradingStatus), 실패한 채점 다시 시도. */
@TestPropertySource(properties={"lit.grading.results-after-end=true","lit.grading.job-interval-ms=3600000","lit.grading.process-interval-ms=3600000"})
class GradingVisibilityApiTest extends AuthTestSupport {
    @Test void resultsAreHiddenUntilTheEvaluationEndsAndFailedGradingCanBeRetried() throws Exception {
        as(teacher);var e=evaluations.create("공개 시점",60);
        var q=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","내용")))).questions().get(0).id();
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        work.save(e.id(),p.participantToken(),q,new SaveAnswer("<p>제 생각을 적은 답안입니다.</p>",0,AnswerSource.DIRECT,null));
        var review=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),review.reviewToken());
        String root="/api/teacher/evaluations/"+e.id();
        var progress=ok(request(get(root+"/grading-status"),teacher));
        assertThat(progress.path("submitted").asInt()).isEqualTo(1);assertThat(progress.path("resultsVisible").asBoolean()).isFalse();
        assertThat(progress.at("/students/0/gradingStatus").asText()).isIn("QUEUED","GRADING");
        for(String path:List.of("/participants/"+p.participantId()+"/rubric-scores","/participants/"+p.participantId()+"/analysis","/students/"+p.participantId()+"/usage-analysis"))
            mvc.perform(request(get(root+path),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("GRADING_RESULTS_NOT_READY"));

        as(teacher);evaluations.end(e.id());
        mvc.perform(request(get(root+"/participants/"+p.participantId()+"/rubric-scores"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("gradingStatus").exists());

        db.update("UPDATE grading_analyses SET analysis_status='DONE',semantic_status='DISABLED' WHERE participant_id=?",p.participantId());
        db.update("UPDATE process_grading_jobs SET status='FAILED',error_code='JUDGE_GEMINI_RATE_LIMITED' WHERE participant_id=?",p.participantId());
        var failed=ok(request(get(root+"/grading-status"),teacher));
        assertThat(failed.at("/students/0/gradingStatus").asText()).isEqualTo("FAILED");assertThat(failed.at("/students/0/errorCode").asText()).isEqualTo("JUDGE_GEMINI_RATE_LIMITED");
        assertThat(failed.path("failed").asInt()).isEqualTo(1);
        var retried=ok(request(post(root+"/participants/"+p.participantId()+"/grading/retry"),teacher));
        assertThat(retried.at("/students/0/gradingStatus").asText()).isEqualTo("GRADING");
        assertThat(db.queryForList("SELECT status FROM process_grading_jobs WHERE participant_id=? ORDER BY version DESC",String.class,p.participantId())).containsExactly("PENDING","FAILED");
        mvc.perform(request(get(root+"/grading-status"),newTeacher())).andExpect(status().isNotFound());
    }
}
