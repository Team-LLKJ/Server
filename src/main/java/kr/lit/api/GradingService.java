package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static kr.lit.api.ReportModels.*;

@Service
@Transactional
public class GradingService {
    private final ReportService reports;
    private final JdbcTemplate db;
    public GradingService(ReportService reports,JdbcTemplate db) {this.reports=reports;this.db=db;}
    private ApiError.Failure fail(String code,String text) {return new ApiError.Failure(HttpStatus.BAD_REQUEST,code,text);}
    public GradingAnswers answers(String id,String pid) {
        var detail=reports.detail(id,pid);var questions=new ArrayList<GradingQuestion>();
        for(var entry:detail.work().path("answers")) {
            var q=entry.path("question");questions.add(new GradingQuestion(q.path("id").asText(),q.path("position").asInt(),q.path("title").asText(),q.path("content").asText(),entry.path("answer")));
        }
        return new GradingAnswers(pid,detail.student().nickname(),detail.student().submittedAt(),detail.student().submissionStatus().equals("SUBMITTED")?"SUBMITTED":"WRITING",questions);
    }
    private Instant time(JsonNode node) {
        if(node.isMissingNode()||node.isNull())return null;
        try{return Instant.parse(node.asText());}catch(DateTimeException ex){return null;}
    }
    public List<Message> conversations(String id,String pid,String questionId,int page,int size) {
        if(page<0||size<1||size>200)throw fail("INVALID_REQUEST","page는0 이상, size는1~200입니다.");
        var detail=reports.detail(id,pid);
        if(questionId!=null) {
            boolean found=false;for(var entry:detail.work().path("answers"))if(entry.path("question").path("id").asText().equals(questionId))found=true;
            if(!found)throw new ApiError.Failure(HttpStatus.NOT_FOUND,"QUESTION_NOT_FOUND","해당 평가의 문항이 아닙니다.");
        }
        var messages=new ArrayList<Message>();
        for(var turn:detail.work().path("conversations")) {
            String qid=turn.path("questionId").asText();if(questionId!=null&&!questionId.equals(qid))continue;
            String key=turn.path("id").asText(turn.path("requestId").asText());
            var created=time(turn.path("createdAt"));var completed=time(turn.path("completedAt"));
            messages.add(new Message(key+"-USER","USER",turn.path("questionText").asText(),qid,created,"COMPLETED",null));
            messages.add(new Message(key+"-AI","AI",turn.path("answerText").isNull()?null:turn.path("answerText").asText(),qid,completed==null?created:completed,turn.path("state").asText(),turn.path("errorCode").isNull()?null:turn.path("errorCode").asText()));
        }
        messages.sort(Comparator.comparing(Message::createdAt,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(m->m.role().equals("USER")?0:1).thenComparing(Message::id));
        return messages.stream().skip((long)page*size).limit(size).toList();
    }
    public Activity activity(String id,String pid) {
        var detail=reports.detail(id,pid);var warnings=new ArrayList<ActivityWarning>();long saves=0,asked=0,completed=0,failed=0;
        for(var event:detail.work().path("learningEvents")) {
            String type=event.path("type").asText();
            switch(type) {case "ANSWER_SAVED"->saves++;case "AI_REQUESTED"->asked++;case "AI_COMPLETED"->completed++;case "AI_FAILED"->failed++;default->{}}
            if(WorkModels.ClientEventType.warningNames().contains(type)) {
                Instant received=time(event.path("createdAt")),occurred=time(event.path("details").path("occurredAt"));
                warnings.add(new ActivityWarning(event.path("id").asLong(),type,event.path("questionId").isNull()?null:event.path("questionId").asText(),occurred==null?received:occurred,received,event.path("details").path("clientReported").asBoolean(false)));
            }
        }
        var purposes=new TreeMap<String,Integer>();int total=0;
        for(var classification:detail.analysis().classifications())for(String purpose:classification.purposes()){purposes.merge(purpose,1,Integer::sum);total++;}
        var usage=new ArrayList<Usage>();
        for(var entry:purposes.entrySet()) {
            double percentage=Math.round(entry.getValue()*1000.0/total)/10.0;
            usage.add(new Usage(purposeLabel(entry.getKey()),percentage,percentage>=50?"높음":percentage>=20?"보통":"낮음",entry.getKey().equals("UNCLASSIFIED")?"neutral":"blue"));
        }
        long warningCount=warnings.size();
        var recent=warnings.stream().skip(Math.max(0,warnings.size()-200)).toList();
        String summary="저장 "+saves+"회, AI 질문 "+asked+"건(완료 "+completed+"건·실패 "+failed+"건), 창 이탈·캡처·복사 시도·전체화면 이탈·개발자 도구 열림 신고 "+warningCount+"건입니다. 신고 기록만으로 부정행위를 판단하지 않습니다.";
        return new Activity(null,usage,recent,warningCount,warningCount>200,summary,detail.analysis().method());
    }
    private String purposeLabel(String purpose) {return switch(purpose) {
        case "IDEATION"->"아이디어 탐색";case "RESEARCH"->"자료 조사";case "STRUCTURING"->"구조화";
        case "VERIFICATION"->"검증";case "REVISION"->"답안 수정";case "ANSWER_DRAFTING"->"답안 생성 요청";
        case "CONCEPT_HELP"->"개념 도움";default->"미분류";
    };}
    public Rubric rubric(String id,String pid) {
        var detail=reports.detail(id,pid);var review=detail.teacherReview();var criteria=new ArrayList<CriterionView>();
        for(var criterion:Criterion.values()) {
            var rating=review==null?null:review.ratings().stream().filter(r->r.criterion()==criterion).findFirst().orElse(null);
            String evidence=rating==null?"":rating.evidence();
            boolean adjusted=(rating!=null&&rating.score()!=null)||db.queryForObject("SELECT COUNT(*) FROM teacher_review_changes WHERE participant_id=? AND criterion_id=?",Integer.class,pid,criterion.name())>0;
            criteria.add(new CriterionView(criterion.name(),ReportPdf.label(criterion),rating==null||rating.score()==null?null:rating.score()*20/review.maxScore(),20,review==null?"교사 검토 전입니다.":review.comment(),evidence==null||evidence.isBlank()?List.of():List.of(evidence),adjusted));
        }
        return new Rubric(review==null?0:review.version(),"DONE",detail.analysis().method(),"PROVISIONAL_FIVE_CRITERIA_0_TO_20",criteria,criteria,reports.reviewHistory(id,pid,0,200));
    }
    public Rubric saveCriterion(String id,String pid,String criterionId,ScoreInput input) {
        // Authenticate before reporting whether the supplied criterion exists.
        reports.teacherReview(id,pid);
        Criterion criterion;
        try {criterion=Criterion.valueOf(criterionId);}catch(IllegalArgumentException ex){throw new ApiError.Failure(HttpStatus.NOT_FOUND,"CRITERION_NOT_FOUND","평가 영역을 찾을 수 없습니다.");}
        reports.saveCriterion(id,pid,criterion,input);return rubric(id,pid);
    }
}
