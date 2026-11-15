package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;

/** 유예를 명시적으로 켠 경우에도 평가 근거와 실제 마감은 보존해야 합니다. */
@TestPropertySource(properties="lit.evaluation.end-grace-seconds=30")
class OptionalGraceSafetyTest extends AuthTestSupport {
    @Autowired AssessmentService assessment;
    record Fixture(String id,String pid,String token,String q) {}
    Fixture fixture() {
        as(teacher);var e=evaluations.create("유예 검증",60);
        var c=work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"문항","문제"))));
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        work.save(e.id(),p.participantToken(),c.questions().get(0).id(),new SaveAnswer("저장 답안",0,AnswerSource.DIRECT,null));
        return new Fixture(e.id(),p.participantId(),p.participantToken(),c.questions().get(0).id());
    }
    @Test void cannotChangeAnswerAfterTeacherScoredAndThenClearedTheScore() {
        var f=fixture();evaluations.end(f.id());
        assessment.saveScore(f.id(),f.pid(),1,new AssessmentModels.ScoreInput(3,"검토"));
        assessment.saveScore(f.id(),f.pid(),1,new AssessmentModels.ScoreInput(null,null));
        assertThatThrownBy(()->work.save(f.id(),f.token(),f.q(),new SaveAnswer("늦은 변경",1,AnswerSource.DIRECT,null)))
                .isInstanceOfSatisfying(ApiError.Failure.class,e->assertThat(e.code).isEqualTo("ALREADY_SUBMITTED"));
        assertThat(submissionByToken(f.id(),f.token()).snapshot().at("/answers/0/answer/content").asText()).isEqualTo("저장 답안");
    }
    @Test void delayedExpiryWorkerDoesNotOpenANewGraceWindowAfterScheduledDeadline() {
        var f=fixture();
        db.update("UPDATE evaluations SET ends_at=DATEADD('SECOND',-100,CURRENT_TIMESTAMP),expires_at=DATEADD('SECOND',-100,CURRENT_TIMESTAMP) WHERE id=?",f.id());
        evaluations.end(f.id());
        assertThatThrownBy(()->work.save(f.id(),f.token(),f.q(),new SaveAnswer("기한 이후 입력",1,AnswerSource.DIRECT,null)))
                .isInstanceOf(ApiError.Failure.class);
        assertThat(submissionByToken(f.id(),f.token()).submissionMode()).isEqualTo("AUTO_DEADLINE");
        assertThat(submissionByToken(f.id(),f.token()).snapshot().at("/answers/0/answer/content").asText()).isEqualTo("저장 답안");
    }
}
