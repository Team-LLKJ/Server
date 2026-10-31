package kr.lit.api;

import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import java.util.List;
import static kr.lit.api.WorkModels.*;

final class QuestionLimits {
    private QuestionLimits() {}
    static void validate(List<QuestionInput> questions) {
        if(questions.stream().map(QuestionInput::position).distinct().count()!=questions.size())
            throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"DUPLICATE_POSITION","문항 순서는 중복될 수 없습니다.");
        for(var q:questions) {
            int min=q.minChars()==null?0:q.minChars();
            if(q.minChars()!=null&&(min<1||min>99999)||q.maxChars()!=null&&(q.maxChars()<1||q.maxChars()>99999||min>q.maxChars()))
                throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_CHARACTER_LIMIT","글자 수는 각각1~99999이며 최소가 최대보다 클 수 없습니다. 생략하면 제한하지 않습니다.");
        }
    }
    static int count(String content) {
        String text=Jsoup.parseBodyFragment(content).text();
        return text.codePointCount(0,text.length());
    }
    static AnswerValidationError error(Question question,Answer answer) {
        int actual=count(answer.content());
        int min=question.minChars()==null?0:question.minChars();
        return actual<min||question.maxChars()!=null&&actual>question.maxChars()
                ?new AnswerValidationError(question.id(),actual,min,question.maxChars()):null;
    }
}
