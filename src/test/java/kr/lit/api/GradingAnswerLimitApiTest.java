package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import static kr.lit.api.WorkModels.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GradingAnswerLimitApiTest extends AuthTestSupport {
    @Test void bothGradingAnswersExposeFrozenQuestionLimitsAndNullForUnlimited()throws Exception {
        as(teacher);var e=evaluations.create("글자 수 제한",60);
        var config=work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"제한 문항","내용",2,20),new QuestionInput(2,"제한 없음","내용"))));
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        for(var q:config.questions())work.save(e.id(),p.participantToken(),q.id(),new SaveAnswer("제출 답안",0,AnswerSource.DIRECT,null));
        var preview=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),preview.reviewToken());
        db.update("UPDATE questions SET min_chars=10,max_chars=100 WHERE id=?",config.questions().get(0).id());
        for(String alias:List.of("students","participants"))mvc.perform(request(get("/api/teacher/evaluations/"+e.id()+"/"+alias+"/"+p.participantId()+"/answers"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("questions[0].minChars").value(2)).andExpect(jsonPath("questions[0].maxChars").value(20)).andExpect(jsonPath("questions[1].minChars").isEmpty()).andExpect(jsonPath("questions[1].maxChars").isEmpty());
    }
}
