package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.*;
import static kr.lit.api.ReportModels.*;
import static kr.lit.api.WorkModels.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class GradingApiTest extends AuthTestSupport {
    @Autowired GradingService grading;
    @Autowired ReportService reports;
    record Fixture(String id,String q,String secondQ,String pid,String token) {}
    Fixture fixture(boolean submit) {
        as(teacher);var e=evaluations.create("채점 연동",60);
        var config=work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"문항 1","첫째 문제"),new QuestionInput(2,"문항 2","둘째 문제"))));
        var p=evaluations.join(e.pin(),"이하랑",null);evaluations.start(e.id());String q=config.questions().get(0).id();
        work.save(e.id(),p.participantToken(),q,new SaveAnswer("<table><tbody><tr><td>표 답안</td><td></td></tr></tbody></table>",0,AnswerSource.DIRECT,null));
        if(submit)submit(e.id(),p.participantToken());
        return new Fixture(e.id(),q,config.questions().get(1).id(),p.participantId(),p.participantToken());
    }
    void submit(String id,String token){var p=work.review(id,token);work.submit(id,token,p.reviewToken());}
    String root(Fixture f){return "/api/teacher/evaluations/"+f.id()+"/students/"+f.pid();}
    @Test void listSupportsSearchDisjointCountsWarningPriorityAndLegacyItems() throws Exception {
        var e=evaluations.create("학생 목록",60);work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"문항","문제"))));
        var first=evaluations.join(e.pin(),"이하랑",null);var second=evaluations.join(e.pin(),"하늘",null);var third=evaluations.join(e.pin(),"별",null);evaluations.start(e.id());
        submit(e.id(),first.participantToken());
        work.recordClientEvent(e.id(),third.participantToken(),new ClientEvent(ClientEventType.WINDOW_LEAVE,Instant.now(),null));submit(e.id(),third.participantToken());
        String path="/api/teacher/evaluations/"+e.id()+"/students";
        var all=ok(request(get(path).param("size","1"),teacher));
        assertThat(all.at("/counts/submitted").asInt()).isEqualTo(1);assertThat(all.at("/counts/warning").asInt()).isEqualTo(1);assertThat(all.at("/counts/writing").asInt()).isEqualTo(1);
        assertThat(all.path("items")).isEqualTo(all.path("students"));assertThat(all.path("students").size()).isEqualTo(1);
        var filtered=ok(request(get(path).param("query","하"),teacher));assertThat(filtered.path("totalElements").asInt()).isEqualTo(2);
        as(teacher);var warning=reports.students(e.id(),0,100).students().stream().filter(s->s.participantId().equals(third.participantId())).findFirst().orElseThrow();
        assertThat(warning.status()).isEqualTo("SUBMITTED");assertThat(warning.submissionStatus()).isEqualTo("SUBMITTED");
        mvc.perform(request(get(path).param("query","%"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(0));
        mvc.perform(request(get(path).param("size","101"),teacher)).andExpect(status().isBadRequest());
    }
    @Test void answersShowLiveDraftThenFrozenSubmittedHtml() throws Exception {
        var f=fixture(false);var draft=grading.answers(f.id(),f.pid());assertThat(draft.status()).isEqualTo("WRITING");assertThat(draft.questions().get(0).answer().path("content").asText()).contains("<table>");
        submit(f.id(),f.token());db.update("UPDATE answers SET content='제출 뒤 라이브 변경' WHERE participant_id=?",f.pid());
        mvc.perform(request(get(root(f)+"/answers"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("status").value("SUBMITTED"))
                .andExpect(jsonPath("questions[0].questionId").value(f.q())).andExpect(jsonPath("questions[0].answer.version").value(1));
        as(teacher);assertThat(grading.answers(f.id(),f.pid()).questions().get(0).answer().path("content").asText()).contains("표 답안").doesNotContain("라이브 변경");
    }
    @Test void conversationMessagesIncludePendingFailureAndQuestionFilter() throws Exception {
        var f=fixture(false);
        var first=work.reserve(f.id(),f.token(),f.q(),new Ask("grading-chat","아이디어를 검증해줘"),"stub","local-stub");work.finish(first,"**힌트**입니다.",null);
        var second=work.reserve(f.id(),f.token(),f.q(),new Ask("grading-failure","힌트"),"stub","local-stub");
        assertThat(grading.conversations(f.id(),f.pid(),f.q(),0,200).stream().map(Message::status)).contains("PENDING");
        work.finish(second,null,"AI_TIMEOUT");
        var messages=grading.conversations(f.id(),f.pid(),f.q(),0,200);
        assertThat(messages).hasSize(4);assertThat(messages.stream().map(Message::role)).contains("USER","AI");
        assertThat(messages.stream().filter(m->m.status().equals("FAILED")).findFirst().orElseThrow().errorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(messages.stream().filter(m->m.role().equals("AI")&&m.status().equals("COMPLETED")).findFirst().orElseThrow().content()).contains("**힌트**");
        mvc.perform(request(get(root(f)+"/conversations").param("questionId",f.secondQ()),teacher)).andExpect(status().isOk()).andExpect(content().json("[]"));
        submit(f.id(),f.token());db.update("UPDATE ai_turns SET answer_text='제출 뒤 변경' WHERE participant_id=?",f.pid());
        as(teacher);assertThat(grading.conversations(f.id(),f.pid(),null,0,200).stream().map(Message::content).filter(Objects::nonNull)).noneMatch(v->v.contains("제출 뒤 변경"));
    }
    @Test void activityContainsClientEventsAndDoesNotInventContribution() throws Exception {
        var f=fixture(false);Instant at=Instant.now();
        for(var type:ClientEventType.values())work.recordClientEvent(f.id(),f.token(),new ClientEvent(type,at,f.q()));
        var turn=work.reserve(f.id(),f.token(),f.q(),new Ask("usage-chat","아이디어를 검증해줘"),"stub","local-stub");work.finish(turn,"응답",null);
        var activity=grading.activity(f.id(),f.pid());assertThat(activity.studentContribution()).isNull();assertThat(activity.warningsTotal()).isEqualTo(10);
        assertThat(activity.warnings().stream().map(ActivityWarning::type)).containsExactly("WINDOW_LEAVE","SHORTCUT_CAPTURE","WINDOW_BLUR","CAPTURE_SHORTCUT","COPY_ATTEMPT","FULLSCREEN_EXIT","FULLSCREEN_LEAVE","DEVTOOLS_OPEN","FULLSCREEN","DEVTOOLS");
        assertThat(activity.warnings().get(0).occurredAt()).isEqualTo(at);assertThat(activity.summary()).contains("AI 질문 1건","신고 10건");
        assertThat(activity.usage().stream().mapToDouble(Usage::percentage).sum()).isEqualTo(100.0);
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/participants/"+f.pid()+"/learning-events"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("$[1].type").value("WINDOW_LEAVE"));
    }
    @Test void criterionScoreSupportsTwentyPointsNullAuditAndVersionConflict() throws Exception {
        var f=fixture(true);var before=grading.rubric(f.id(),f.pid());assertThat(before.criteria()).hasSize(5);assertThat(before.criteria()).allMatch(c->c.score()==null&&c.maxScore()==20);
        String path=root(f)+"/review/criteria/PURPOSE_CLARITY";
        mvc.perform(body(request(put(path),teacher),Map.of("score",18,"expectedVersion",0))).andExpect(status().isOk()).andExpect(jsonPath("criteria[0].score").value(18)).andExpect(jsonPath("criteria[0].adjusted").value(true));
        mvc.perform(body(request(put(path),teacher),Map.of("score",16,"expectedVersion",1))).andExpect(status().isOk());
        mvc.perform(body(request(put(path),teacher),Map.of("score",10,"expectedVersion",1))).andExpect(status().isConflict());
        var clearScore=new HashMap<String,Object>();clearScore.put("score",null);clearScore.put("expectedVersion",2);
        mvc.perform(body(request(put(path),teacher),clearScore)).andExpect(status().isOk()).andExpect(jsonPath("criteria[0].score").isEmpty());
        as(teacher);var history=reports.reviewHistory(f.id(),f.pid(),0,100);assertThat(history).hasSize(3);assertThat(history.get(1).previousScore()).isEqualTo(18);assertThat(history.get(1).score()).isEqualTo(16);
        assertThat(history.get(2).score()).isNull();assertThat(history.get(2).teacherId()).isEqualTo(teacher.user().id());assertThat(history.get(2).maxScore()).isEqualTo(20);
        assertThat(reports.reviewHistory(f.id(),f.pid(),history.get(0).id(),1)).hasSize(1);
        mvc.perform(body(request(put(path),teacher),Map.of())).andExpect(status().isBadRequest());
        mvc.perform(body(request(put(path),teacher),Map.of("score",21))).andExpect(status().isBadRequest());
    }
    @Test void oldFourPointReviewsAreScaledWithoutLosingEvidenceOrAuditingRescaleAsAnEdit() {
        var f=fixture(true);var input=new ReviewInput(0,Compliance.COMPLIANT,Arrays.stream(Criterion.values()).map(c->new Rating(c,3,"교사 근거")).toList(),"기존 의견");
        reports.saveReview(f.id(),f.pid(),input);var old=reports.teacherGenerate(f.id(),f.pid());
        assertThat(grading.rubric(f.id(),f.pid()).criteria()).allMatch(c->c.score()==15&&c.maxScore()==20);
        var next=grading.saveCriterion(f.id(),f.pid(),"PURPOSE_CLARITY",new ScoreInput(18,1));assertThat(next.criteria().get(1).score()).isEqualTo(15);
        var changes=reports.reviewHistory(f.id(),f.pid(),0,100);assertThat(changes).hasSize(6);var changed=changes.get(5);
        assertThat(changed.previousScore()).isEqualTo(3);assertThat(changed.previousMaxScore()).isEqualTo(4);assertThat(changed.score()).isEqualTo(18);assertThat(changed.maxScore()).isEqualTo(20);
        assertThat(next.criteria().get(0).evidence()).contains("교사 근거");assertThat(reports.teacherGenerate(f.id(),f.pid()).content().teacherReview().maxScore()).isEqualTo(20);
        assertThat(reports.teacherReport(f.id(),f.pid(),old.version()).content().teacherReview().maxScore()).isEqualTo(4);
    }
    @Test void legacyReviewJsonWithoutScaleStillReadsAsFourPoints() {
        var f=fixture(true);
        var legacy=new TeacherReview(1,Compliance.COMPLIANT,Arrays.stream(Criterion.values()).map(c->new Rating(c,3,"기존 근거")).toList(),"기존 교사 의견",teacher.user().id(),Instant.now());
        var stored=json.valueToTree(legacy);((com.fasterxml.jackson.databind.node.ObjectNode)stored).remove("maxScore");
        db.update("INSERT INTO teacher_reviews VALUES (?,?,?,CURRENT_TIMESTAMP)",f.pid(),f.id(),stored.toString());
        assertThat(grading.rubric(f.id(),f.pid()).criteria()).allMatch(c->c.score()==15&&c.maxScore()==20&&c.adjusted());
        assertThat(reports.reviewHistory(f.id(),f.pid(),0,100)).isEmpty();
        grading.saveCriterion(f.id(),f.pid(),"PURPOSE_CLARITY",new ScoreInput(18,1));
        var changes=reports.reviewHistory(f.id(),f.pid(),0,100);assertThat(changes).hasSize(1);assertThat(changes.get(0).previousScore()).isEqualTo(3);assertThat(changes.get(0).previousMaxScore()).isEqualTo(4);
    }
    @Test void simultaneousExpectedVersionChangesHaveOneWinner() throws Exception {
        var f=fixture(true);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> update=()->{as(teacher);gate.await();try{grading.saveCriterion(f.id(),f.pid(),"PURPOSE_CLARITY",new ScoreInput(18,0));return true;}
            catch(ApiError.Failure e){assertThat(e.code).isEqualTo("REVIEW_VERSION_CONFLICT");return false;}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}};
        try{var one=pool.submit(update);var two=pool.submit(update);gate.countDown();assertThat(List.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(reports.reviewHistory(f.id(),f.pid(),0,100)).hasSize(1);
        }finally{pool.shutdownNow();}
    }
    @Test void scopedEndpointsRejectOtherTeacherAndUnsubmittedScoring() throws Exception {
        var f=fixture(false);var other=newTeacher();
        for(String suffix:List.of("/answers","/conversations","/activity","/review","/review/history","/report.pdf")) {
            mvc.perform(request(get(root(f)+suffix),other)).andExpect(status().isNotFound());mvc.perform(get(root(f)+suffix)).andExpect(status().isUnauthorized());
        }
        mvc.perform(body(request(put(root(f)+"/review/criteria/PURPOSE_CLARITY"),teacher),Map.of("score",18))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("SUBMISSION_REQUIRED"));
        mvc.perform(request(get(root(f)+"/conversations").param("questionId","unknown"),teacher)).andExpect(status().isNotFound());
        mvc.perform(request(get(root(f)+"/report.pdf"),teacher)).andExpect(status().isConflict());
        mvc.perform(body(request(put(root(f)+"/review/criteria/UNKNOWN"),teacher),Map.of("score",18))).andExpect(status().isNotFound());
    }
    @Test void pdfAliasIncludesPartialScoresReadableTableAndCreatesCurrentReport() throws Exception {
        var f=fixture(true);grading.saveCriterion(f.id(),f.pid(),"PURPOSE_CLARITY",new ScoreInput(18,0));
        var result=mvc.perform(request(get(root(f)+"/report.pdf"),teacher)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        try(var doc=org.apache.pdfbox.Loader.loadPDF(result)) {
            String text=new org.apache.pdfbox.text.PDFTextStripper().getText(doc);assertThat(text).contains("18 / 20","미채점","표 답안","[빈 셀]").doesNotContain("<table>");
            javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0,100),"png",java.nio.file.Path.of("/tmp/lit-grading-review.png").toFile());
        }
        mvc.perform(request(get(root(f)+"/report/pdf"),teacher)).andExpect(status().isOk());
    }
}
