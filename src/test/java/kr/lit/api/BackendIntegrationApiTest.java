package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.validation.Validator;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static kr.lit.api.AssessmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BackendIntegrationApiTest extends AuthTestSupport {
    @Autowired AssessmentService assessment;
    @Autowired GradingService grading;
    @Autowired Validator validator;
    record Fixture(String id,String pid,String token,String q) {}
    Fixture fixture() {
        as(teacher);var e=evaluations.create("백엔드 통합",60);
        var config=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","광합성을 설명하세요."))));
        var p=evaluations.join(e.pin(),"검증 학생",null);evaluations.start(e.id());
        work.save(e.id(),p.participantToken(),config.questions().get(0).id(),new SaveAnswer("내가 정리한 개념과 관찰 기록입니다.",0,AnswerSource.DIRECT,null));
        return new Fixture(e.id(),p.participantId(),p.participantToken(),config.questions().get(0).id());
    }
    void submit(Fixture f) {var p=work.review(f.id(),f.token());work.submit(f.id(),f.token(),p.reviewToken());}
    @Test void absentAiEvidenceDoesNotAwardFullMarksOrInventContribution() {
        var f=fixture();submit(f);var scores=assessment.scores(f.id(),f.pid());var analysis=assessment.analysis(f.id(),f.pid());
        assertThat(scores.criteria()).allMatch(c->c.ai()==null);assertThat(scores.totalAi()).isNull();assertThat(scores.recommendedMaxScore()).isZero();
        assertThat(scores.missingAiCriteria()).hasSize(4);assertThat(analysis.indicators()).allMatch(i->i.level()==null&&i.value()==null);
        assertThat(analysis.contribution().basis()).isEqualTo("TEXT_SIMILARITY_ESTIMATE_V2");assertThat(grading.activity(f.id(),f.pid()).studentContribution()).isNull();
        assertThatThrownBy(()->assessment.confirm(f.id(),f.pid(),true)).isInstanceOfSatisfying(ApiError.Failure.class,e->assertThat(e.code).isEqualTo("GRADING_INCOMPLETE"));
        assertThat(assessment.scores(f.id(),f.pid()).status()).isEqualTo("NOT_STARTED");
    }
    @Test void defaultRubricIsEditableDraftAndCannotBeResetAfterClearingScores() {
        var f=fixture();var rubric=assessment.rubric(f.id());assertThat(rubric.criteria().stream().map(Criterion::maxScore)).containsExactly(20,30,20,30);
        assertThat(rubric.criteria()).allMatch(c->c.basis().contains("초안"));submit(f);
        assessment.saveScore(f.id(),f.pid(),1,new ScoreInput(2,"교사 검토"));assessment.saveScore(f.id(),f.pid(),1,new ScoreInput(null,null));
        assertThat(assessment.rubric(f.id()).locked()).isTrue();
        assertThatThrownBy(()->assessment.resetRubric(f.id())).isInstanceOfSatisfying(ApiError.Failure.class,e->assertThat(e.code).isEqualTo("RUBRIC_LOCKED"));
    }
    @Test void evidenceBackedRecommendationsRemainAdvisoryAndNeedManualContributionScore() {
        var f=fixture();
        var one=work.reserve(f.id(),f.token(),f.q(),new Ask("concept","광합성 개념을 설명해줘"),"stub","local-stub");work.finish(one,"빛을 사용해 양분을 만드는 과정입니다.",null);
        var two=work.reserve(f.id(),f.token(),f.q(),new Ask("verify","방금 설명의 출처와 근거가 맞는지 확인해줘"),"stub","local-stub");work.finish(two,"출처를 함께 대조하세요.",null);
        submit(f);
        var scores=assessment.scores(f.id(),f.pid());assertThat(scores.recommendedMaxScore()).isEqualTo(70);
        assertThat(scores.criteria().get(3).ai()).isNull();assertThat(scores.status()).isEqualTo("NOT_STARTED");
        assertThatThrownBy(()->assessment.confirm(f.id(),f.pid(),true)).isInstanceOf(ApiError.Failure.class);
        assessment.saveScore(f.id(),f.pid(),4,new ScoreInput(3,"답안의 고유 해석을 교사가 확인함"));
        var confirmed=assessment.confirm(f.id(),f.pid(),true);assertThat(confirmed.status()).isEqualTo("CONFIRMED");assertThat(confirmed.confirmedTotal()).isNotNull();
    }
    @Test void staleAnalysisWorkerCannotOverwriteNewerRequestOrCompletedResult() {
        var f=fixture();submit(f);
        var old=db.queryForObject("SELECT requested_at FROM grading_analyses WHERE participant_id=?",java.time.OffsetDateTime.class,f.pid());
        db.update("UPDATE grading_analyses SET requested_at=DATEADD('SECOND',1,requested_at) WHERE participant_id=?",f.pid());
        assessment.analyzeForJob(f.id(),f.pid(),old);assessment.markFailed(f.pid(),old,"OLD_FAILURE");
        assertThat(db.queryForObject("SELECT analysis_status FROM grading_analyses WHERE participant_id=?",String.class,f.pid())).isEqualTo("PENDING");
        var latest=db.queryForObject("SELECT requested_at FROM grading_analyses WHERE participant_id=?",java.time.OffsetDateTime.class,f.pid());
        assessment.analyzeForJob(f.id(),f.pid(),latest);assessment.markFailed(f.pid(),latest,"LATE_FAILURE");
        assertThat(db.queryForObject("SELECT analysis_status FROM grading_analyses WHERE participant_id=?",String.class,f.pid())).isEqualTo("DONE");
    }
    @Test void invalidGradingConfigurationFailsValidation() {
        var config=new GradingConfig();assertThat(validator.validate(config)).isEmpty();
        config.setAiReferencedThreshold(0);assertThat(validator.validate(config)).isNotEmpty();
        config.setAiReferencedThreshold(0.4);config.setMinSpanLength(2);assertThat(validator.validate(config)).isNotEmpty();config.setMinSpanLength(20);
        config.setAiReferencedThreshold(0.4);config.setUsageHighShare(Double.NaN);assertThat(validator.validate(config)).isNotEmpty();
    }}
