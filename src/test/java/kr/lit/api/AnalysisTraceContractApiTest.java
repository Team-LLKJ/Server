package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.time.Instant;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisTraceContractApiTest extends AuthTestSupport {
    @Autowired AssessmentService assessment;
    @Autowired GradingService grading;
    record Fixture(String id,String pid,String token,String q) {}
    Fixture fixture(){as(teacher);var e=evaluations.create("근거 구간",60);var q=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","설명하세요.")))).questions().get(0).id();var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());return new Fixture(e.id(),p.participantId(),p.participantToken(),q);}
    String root(Fixture f){return "/api/teacher/evaluations/"+f.id()+"/participants/"+f.pid();}
    void ask(Fixture f,String key,String question,String answer){var turn=work.reserve(f.id(),f.token(),f.q(),new Ask(key,question),"stub","test");work.finish(turn,answer,null);}
    void submit(Fixture f){var review=work.review(f.id(),f.token());work.submit(f.id(),f.token(),review.reviewToken());as(teacher);}
    @Test void structuredReasonsQuotesAndSegmentsMatchActualStoredMessagesAndPercents()throws Exception {
        var f=fixture();ask(f,"concept","긴 앞말입니다. 광합성의 개념을 설명해줘.","식물은 빛으로 양분을 만듭니다.");ask(f,"draft","답안을 대신 작성해줘.","배식량을 선택하도록 제안합니다.");
        work.save(f.id(),f.token(),f.q(),new SaveAnswer("<p>배식량을 선택하도록 제안합니다.</p><p>나의 생각😀은 학교 상황을 먼저 확인하는 것입니다.</p>",0,AnswerSource.DIRECT,null));submit(f);
        var analysis=assessment.analysis(f.id(),f.pid());var messages=assessment.conversations(f.id(),f.pid(),null,0,200);
        assertThat(analysis.indicators().get(0).reasons().stream().map(AssessmentModels.Reason::type)).contains("CREDIT","DEDUCTION");
        for(var indicator:analysis.indicators())for(var evidence:indicator.evidence())if(evidence.conversationId()!=null){var message=messages.stream().filter(m->m.conversationId()==evidence.conversationId()).findFirst().orElseThrow();assertThat(evidence.type()).isIn("CREDIT","DEDUCTION");assertThat(evidence.quote()).isNotBlank();assertThat(evidence.messageRole().equals("USER")?message.question():message.answer()).contains(evidence.quote());}
        var segments=analysis.contribution().segments();assertThat(segments).isNotEmpty();var text=analysis.answerTexts().get(0).text();long total=0,direct=0;
        for(var segment:segments){assertThat(segment.estimated()).isTrue();assertThat(text.substring(segment.start(),segment.end())).isNotEmpty();assertThat(segment.answerVersion()).isEqualTo(1);total+=segment.end()-segment.start();if(segment.type().equals("STUDENT"))direct+=segment.end()-segment.start();}
        assertThat(total).isEqualTo(text.replace("\n","").length());assertThat(analysis.contribution().direct()).isBetween((int)Math.floor(direct*100.0/total),(int)Math.ceil(direct*100.0/total));assertThat(analysis.contribution().direct()+analysis.contribution().aiReferenced()+analysis.contribution().aiUsed()).isEqualTo(100);
        var scores=assessment.scores(f.id(),f.pid());assertThat(scores.criteria().get(0).ai().reasons()).isNotEmpty();assertThat(scores.summary()).contains("AI 질문 2건","답안 작성 요청 1건");
    }
    @Test void pasteRangesAreVersionedAndOldObservationsDoNotPretendToMatchFinalAnswer()throws Exception {
        var f=fixture();work.save(f.id(),f.token(),f.q(),new SaveAnswer("😀가나다",0,AnswerSource.DIRECT,null));String path="/api/evaluations/"+f.id()+"/events";
        var body=new HashMap<String,Object>(Map.of("type","PASTE","occurredAt",Instant.now().toString(),"questionId",f.q(),"length",2,"start",1,"end",2,"answerVersion",1));
        mvc.perform(body(post(path).header("X-Participant-Token",f.token()),body)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_PASTE_RANGE"));
        body.put("start",0);body.put("answerVersion",0);mvc.perform(body(post(path).header("X-Participant-Token",f.token()),body)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("PASTE_VERSION_CONFLICT"));
        body.put("answerVersion",1);mvc.perform(body(post(path).header("X-Participant-Token",f.token()),body)).andExpect(status().isCreated());work.save(f.id(),f.token(),f.q(),new SaveAnswer("바뀐 답안",1,AnswerSource.DIRECT,null));submit(f);
        var analysis=assessment.analysis(f.id(),f.pid());assertThat(analysis.pasteEvents()).hasSize(1);assertThat(analysis.pasteEvents().get(0).matchesFinalAnswer()).isFalse();assertThat(grading.activity(f.id(),f.pid()).warningsTotal()).isZero();
    }
    @Test void usageExcludesUnclassifiedAndWarnedAutomaticSubmissionStaysSubmitted()throws Exception {
        var f=fixture();ask(f,"unknown","안녕","안녕");ask(f,"concept","개념을 설명해줘","개념");ask(f,"research","자료를 찾아줘","자료");work.recordClientEvent(f.id(),f.token(),new ClientEvent(ClientEventType.WINDOW_LEAVE,Instant.now(),null));as(teacher);evaluations.end(f.id());
        var activity=grading.activity(f.id(),f.pid());assertThat(activity.usage()).noneMatch(u->u.colorKey().equals("neutral"));assertThat(activity.usage().stream().mapToDouble(ReportModels.Usage::percentage).sum()).isCloseTo(100,within(.0001));assertThat(activity.usageAnalysis().usage()).noneMatch(group->group.purpose()==PurposeUsageAnalyzer.Purpose.OTHER);assertThat(activity.usageAnalysis().otherCount()).isEqualTo(1);assertThat(activity.usage()).allMatch(group->group.level()==null);
        var row=assessment.submissions(f.id(),0,100,"").items().get(0);assertThat(row.status()).isEqualTo("SUBMITTED");assertThat(row.submissionStatus()).isEqualTo("SUBMITTED");assertThat(row.submittedAt()).isNotNull();assertThat(row.lastWarningAt()).isNotNull();
    }
}
