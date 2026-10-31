package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QuestionLimitsApiTest extends AuthTestSupport {
    record Fixture(String id,String q,String token) {}
    Fixture fixture(int min,Integer max) {
        var e=evaluations.create("글자 수",60);
        var config=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","문제",min==0?null:min,max))));
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        return new Fixture(e.id(),config.questions().get(0).id(),p.participantToken());
    }
    @Test void shortDraftIsSavedButManualSubmissionNeedsMinimumAndHtmlIsNotCounted() throws Exception {
        var f=fixture(3,5);
        work.save(f.id(),f.token(),f.q(),new SaveAnswer("<p>가😀</p>",0,AnswerSource.DIRECT,null));
        var review=work.review(f.id(),f.token());assertThat(review.canConfirm()).isFalse();assertThat(review.answerValidationErrors()).hasSize(1);
        assertThat(review.answerValidationErrors().get(0).actualChars()).isEqualTo(2);
        mvc.perform(body(post("/api/student/evaluations/"+f.id()+"/submission").header("X-Participant-Token",f.token()),Map.of("reviewToken",review.reviewToken()))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("ANSWER_LENGTH_INVALID"));
        work.save(f.id(),f.token(),f.q(),new SaveAnswer("<p>가😀나</p>",1,AnswerSource.DIRECT,null));
        var good=work.review(f.id(),f.token());assertThat(good.canConfirm()).isTrue();work.submit(f.id(),f.token(),good.reviewToken());
        assertThat(work.submission(f.id(),f.token()).snapshot().path("answers").get(0).path("question").path("minChars").asInt()).isEqualTo(3);
    }
    @Test void maximumIsEnforcedWithoutDestroyingPreviouslySavedAnswer() throws Exception {
        var f=fixture(0,3);work.save(f.id(),f.token(),f.q(),new SaveAnswer("가😀나",0,AnswerSource.DIRECT,null));
        mvc.perform(body(put("/api/student/evaluations/"+f.id()+"/questions/"+f.q()+"/answer").header("X-Participant-Token",f.token()),new SaveAnswer("<p>가😀나다</p>",1,AnswerSource.DIRECT,null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("ANSWER_TOO_LONG"));
        var a=work.workspace(f.id(),f.token()).questions().get(0).answer();assertThat(a.content()).isEqualTo("가😀나");assertThat(a.version()).isEqualTo(1);
    }
    @Test void automaticEndKeepsShortSavedDraftWithoutDiscardingIt() {
        var f=fixture(20,100);work.save(f.id(),f.token(),f.q(),new SaveAnswer("짧은 초안",0,AnswerSource.DIRECT,null));
        evaluations.end(f.id());var submitted=work.submission(f.id(),f.token());assertThat(submitted.submissionMode()).isEqualTo("AUTO_TEACHER_END");
        assertThat(submitted.snapshot().path("answers").get(0).path("answer").path("content").asText()).isEqualTo("짧은 초안");
    }
    @Test void invalidLimitsDoNotReplaceExistingQuestions() throws Exception {
        var e=evaluations.create("검증",60);var original=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"원본","본문"))));
        mvc.perform(body(request(put("/api/teacher/evaluations/"+e.id()+"/configuration"),teacher),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"잘못된 문항","본문",50,10)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_CHARACTER_LIMIT"));
        as(teacher);assertThat(work.configuration(e.id()).questions().get(0).id()).isEqualTo(original.questions().get(0).id());
    }
    @Test void omittedLimitsKeepLegacyUnlimitedMaximumAndZeroMinimum() {
        var f=fixture(0,null);assertThat(work.configuration(f.id()).questions().get(0).maxChars()).isNull();
        var review=work.review(f.id(),f.token());assertThat(review.answerValidationErrors()).isEmpty();assertThat(review.canConfirm()).isTrue();
    }

}
