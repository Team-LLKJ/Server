package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 채점 화면 신규 API(2·7장), 평가 수정·삭제(3장), 경고 이벤트(5장), 종료 유예 저장. */
class AssessmentApiTest extends AuthTestSupport {
    @Autowired AssessmentAnalyzer analyzer;
    @Autowired AssessmentService grading;
    @Autowired ReportService reports;
    @Autowired ReportPdf pdf;
    @Autowired ReportHwpService hwp;

    record Fixture(String id,String q1,String q2,String pid,String token,String otherPid,String otherToken) {}
    Fixture fixture(AiPolicy policy) {
        as(teacher);var e=evaluations.create("AI 활용 평가",60);
        var config=work.configure(e.id(),new Configure(policy,List.of(new QuestionInput(1,"문항 1","광합성을 설명하세요."),new QuestionInput(2,"문항 2","예시를 드세요."))));
        var p=evaluations.join(e.pin(),"이하랑",null);var o=evaluations.join(e.pin(),"김별",null);evaluations.start(e.id());
        return new Fixture(e.id(),config.questions().get(0).id(),config.questions().get(1).id(),p.participantId(),p.participantToken(),o.participantId(),o.participantToken());
    }
    static List<String> texts(JsonNode array,String field) {
        var out=new ArrayList<String>();for(var n:array) out.add(n.path(field).asText());return out;
    }
    String root(Fixture f) { return "/api/teacher/evaluations/"+f.id(); }
    void submit(String id,String token) { var p=work.review(id,token);work.submit(id,token,p.reviewToken()); }
    String ask(Fixture f,String q,String requestId,String text) {
        var r=work.reserve(f.id(),f.token(),q,new Ask(requestId,text),"stub","local-stub");
        return work.finish(r,"광합성은 식물이 빛 에너지를 이용해 이산화탄소와 물로 포도당을 만드는 과정입니다",null).answerText();
    }

    @Test void classifierAndParagraphSplitFollowRules() {
        assertThat(AssessmentAnalyzer.classify("답안을 대신 작성해줘")).isEqualTo(AssessmentModels.QuestionType.ANSWER_DRAFTING);
        assertThat(AssessmentAnalyzer.classify("이 설명이 맞는지 근거를 확인해줘")).isEqualTo(AssessmentModels.QuestionType.VERIFICATION);
        assertThat(AssessmentAnalyzer.classify("관련 자료를 찾아줘")).isEqualTo(AssessmentModels.QuestionType.RESEARCH);
        assertThat(AssessmentAnalyzer.classify("주제 아이디어를 구체화하고 싶어")).isEqualTo(AssessmentModels.QuestionType.IDEA_EXPLORATION);
        assertThat(AssessmentAnalyzer.classify("광합성의 개념을 설명해줘")).isEqualTo(AssessmentModels.QuestionType.CONCEPT_UNDERSTANDING);
        assertThat(AssessmentAnalyzer.classify("안녕")).isEqualTo(AssessmentModels.QuestionType.OTHER);
        assertThat(AssessmentAnalyzer.paragraphs("<p>첫째</p><ul><li>둘째</li></ul><table><tr><td>a</td><td>b</td></tr></table>")).containsExactly("첫째","둘째","a | b");
        assertThat(AssessmentAnalyzer.paragraphs("일반\n\n텍스트")).containsExactly("일반","텍스트");
        assertThat(AssessmentAnalyzer.containment("광합성은 빛 에너지를 이용한다","광합성은 빛 에너지를 이용한다")).isEqualTo(1.0);
    }

    @Test void gradingFlowFromSubmissionToConfirmation() throws Exception {
        var f=fixture(AiPolicy.HINT_ONLY);
        work.save(f.id(),f.token(),f.q1(),new SaveAnswer("<p>광합성은 빛으로 양분을 만드는 과정이라고 생각한다.</p>",0,AnswerSource.DIRECT,null));
        ask(f,f.q1(),"r1","광합성의 개념을 설명해줘");
        ask(f,f.q1(),"r2","방금 설명이 맞는지 근거를 확인해줘");
        String aiText=ask(f,f.q2(),"r3","답안을 대신 작성해줘");
        work.recordClientEvent(f.id(),f.token(),new ClientEvent(ClientEventType.COPY_ATTEMPT,Instant.now(),null));
        work.save(f.id(),f.token(),f.q2(),new SaveAnswer("<p>"+aiText+"</p><p>학교 화단의 나팔꽃이 좋은 예시다.</p>",0,AnswerSource.AI_USED,"r3"));
        work.saveDisclosure(f.id(),f.token(),new Disclosure("LIT 내장 AI","문항 2 첫 문단","개념 확인 후 내 예시로 바꿨다"));
        submit(f.id(),f.token());

        JsonNode subs=ok(request(get(root(f)+"/submissions"),teacher));
        JsonNode mine=null,other=null;
        for(var row:subs.path("items")) { if(row.path("participantId").asText().equals(f.pid())) mine=row; else other=row; }
        assertThat(mine.path("status").asText()).isEqualTo("SUBMITTED");assertThat(mine.path("warningCount").asInt()).isEqualTo(1);
        assertThat(mine.path("totalAi").isNumber()).isTrue();assertThat(mine.path("gradingStatus").asText()).isEqualTo("NOT_STARTED");
        assertThat(other.path("status").asText()).isEqualTo("WRITING");assertThat(other.path("totalScore").isNull()).isTrue();

        JsonNode answers=ok(request(get(root(f)+"/participants/"+f.pid()+"/answers"),teacher));
        JsonNode q2=answers.path("questions").get(1);
        assertThat(q2.path("paragraphs").get(0).path("source").asText()).isEqualTo("AI_USED");
        assertThat(q2.path("paragraphs").get(1).path("source").asText()).isEqualTo("DIRECT");

        JsonNode conv=ok(request(get(root(f)+"/participants/"+f.pid()+"/conversations"),teacher));
        assertThat(texts(conv,"questionType")).containsExactly("CONCEPT_UNDERSTANDING","VERIFICATION","ANSWER_DRAFTING");

        JsonNode analysis=ok(request(get(root(f)+"/participants/"+f.pid()+"/analysis"),teacher));
        assertThat(analysis.path("status").asText()).isEqualTo("DONE");
        assertThat(analysis.at("/compliance/result").asText()).isEqualTo("CHECK");
        var c=analysis.path("contribution");
        assertThat(c.path("direct").asInt()+c.path("aiReferenced").asInt()+c.path("aiUsed").asInt()).isEqualTo(100);
        mvc.perform(request(get(root(f)+"/participants/"+f.otherPid()+"/analysis"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("status").value("NOT_SUBMITTED"));

        String score=root(f)+"/participants/"+f.pid()+"/rubric-scores";
        mvc.perform(request(get(score),teacher)).andExpect(status().isOk()).andExpect(jsonPath("criteria.length()").value(5))
                .andExpect(jsonPath("criteria[0].maxScore").value(15)).andExpect(jsonPath("status").value("NOT_STARTED"));
        mvc.perform(body(request(put(score+"/1"),teacher),Map.of("level",3,"feedback","목적 적절"))).andExpect(status().isOk())
                .andExpect(jsonPath("criteria[0].teacher.score").value(11.25)).andExpect(jsonPath("status").value("DRAFT"));
        mvc.perform(body(request(put(score+"/9"),teacher),Map.of("level",3))).andExpect(status().isNotFound());
        mvc.perform(body(request(put(score+"/2"),teacher),Map.of("level",5))).andExpect(status().isBadRequest());
        mvc.perform(body(request(put(root(f)+"/participants/"+f.otherPid()+"/rubric-scores/1"),teacher),Map.of("level",3))).andExpect(status().isConflict());
        mvc.perform(request(post(root(f)+"/participants/"+f.pid()+"/grade/confirm"),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("GRADING_INCOMPLETE"));
        for(int id=2;id<=5;id++) mvc.perform(body(request(put(score+"/"+id),teacher),Map.of("level",3))).andExpect(status().isOk());
        mvc.perform(request(post(root(f)+"/participants/"+f.pid()+"/grade/confirm"),teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("CONFIRMED")).andExpect(jsonPath("confirmedTotal").value(75.0));
        mvc.perform(body(request(put(score+"/2"),teacher),Map.of("level",4))).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("DRAFT")).andExpect(jsonPath("confirmedTotal").value(75.0)).andExpect(jsonPath("totalTeacher").value(81.25));
        mvc.perform(body(request(put(root(f)+"/participants/"+f.pid()+"/compliance-review"),teacher),Map.of("decision","HOLD")))
                .andExpect(status().isOk()).andExpect(jsonPath("compliance.decision").value("HOLD")).andExpect(jsonPath("compliance.ai").value("CHECK"));
        mvc.perform(request(get(score+"/history"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("length()").value(6));
        mvc.perform(body(request(put(root(f)+"/rubric"),teacher),Map.of("criteria",List.of(Map.of("name","a","maxScore",100,"indicator","MANUAL")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("RUBRIC_LOCKED"));
        mvc.perform(request(post(root(f)+"/analysis/run"),teacher)).andExpect(status().isAccepted()).andExpect(jsonPath("queued").value(1));
        var stranger=newTeacher();
        mvc.perform(request(get(root(f)+"/submissions"),stranger)).andExpect(status().isNotFound());
    }

    @Test void rubricCanBeCustomizedUntilScoringStartsAndMustTotalHundred() throws Exception {
        var f=fixture(AiPolicy.FULL);String path=root(f)+"/rubric";
        mvc.perform(request(get(path),teacher)).andExpect(status().isOk()).andExpect(jsonPath("totalScore").value(100)).andExpect(jsonPath("custom").value(false));
        mvc.perform(body(request(put(path),teacher),Map.of("criteria",List.of(Map.of("name","a","maxScore",50,"indicator","PURPOSE"))))).andExpect(status().isBadRequest());
        mvc.perform(body(request(put(path),teacher),Map.of("criteria",List.of(Map.of("name","기여","maxScore",60,"indicator","CONTRIBUTION"),Map.of("name","발표","maxScore",40,"indicator","MANUAL")))))
                .andExpect(status().isOk()).andExpect(jsonPath("custom").value(true)).andExpect(jsonPath("criteria[1].criterionId").value(2));
        submit(f.id(),f.token());
        mvc.perform(request(get(root(f)+"/participants/"+f.pid()+"/rubric-scores"),teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("criteria[1].ai").doesNotExist());
        mvc.perform(request(delete(path),teacher)).andExpect(status().isOk()).andExpect(jsonPath("criteria.length()").value(5));
    }

    @Test void warningEventsAcceptNewTypesAndCountInSubmissions() throws Exception {
        var f=fixture(AiPolicy.FULL);String path="/api/evaluations/"+f.id()+"/learning-events";
        for(String type:List.of("WINDOW_BLUR","CAPTURE_SHORTCUT","COPY_ATTEMPT","PASTE","AI_ANSWER_COPY"))
            mvc.perform(body(post(path).header("X-Participant-Token",f.token()),Map.of("type",type,"occurredAt",Instant.now().toString(),"questionId",f.q1())))
                    .andExpect(status().isCreated()).andExpect(jsonPath("type").value(type));
        mvc.perform(body(post(path).header("X-Participant-Token",f.token()),Map.of("type","AI_COMPLETED","occurredAt",Instant.now().toString()))).andExpect(status().isBadRequest());
        mvc.perform(body(post(path).header("X-Participant-Token",f.token()),Map.of("type","PASTE","occurredAt",Instant.now().toString(),"conversationId",999999))).andExpect(status().isNotFound());
        var row=ok(request(get(root(f)+"/submissions"),teacher)).path("items");
        for(var r:row) if(r.path("participantId").asText().equals(f.pid())) assertThat(r.path("warningCount").asInt()).isEqualTo(3);
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/participants/"+f.pid()+"/learning-events"),teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("WINDOW_BLUR"));
    }

    @Test void notificationsSettingsAndProfile() throws Exception {
        var mine=newTeacher();as(mine);var e=evaluations.create("알림",60);
        work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"q","c"))));
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        work.recordClientEvent(e.id(),p.participantToken(),new ClientEvent(ClientEventType.WINDOW_BLUR,Instant.now(),null));
        work.recordClientEvent(e.id(),p.participantToken(),new ClientEvent(ClientEventType.COPY_ATTEMPT,Instant.now(),null));
        submit(e.id(),p.participantToken());as(mine);evaluations.end(e.id());
        mvc.perform(request(get("/api/notifications/unread-count"),mine)).andExpect(status().isOk()).andExpect(jsonPath("unreadCount").value(3));
        var page=ok(request(get("/api/notifications"),mine));
        assertThat(texts(page.path("items"),"type")).containsExactly("EVALUATION_ENDED","SUBMISSION","WARNING");
        long id=page.path("items").get(0).path("id").asLong();
        mvc.perform(request(patch("/api/notifications/"+id+"/read"),mine)).andExpect(status().isOk()).andExpect(jsonPath("read").value(true));
        mvc.perform(request(patch("/api/notifications/"+id+"/read"),teacher)).andExpect(status().isNotFound());
        mvc.perform(request(post("/api/notifications/read-all"),mine)).andExpect(status().isOk()).andExpect(jsonPath("unreadCount").value(0));
        mvc.perform(request(get("/api/users/me/settings"),mine)).andExpect(status().isOk()).andExpect(jsonPath("notify").value(true)).andExpect(jsonPath("sound").value(true));
        mvc.perform(body(request(put("/api/users/me/settings"),mine),Map.of("notify",false))).andExpect(status().isOk())
                .andExpect(jsonPath("notify").value(false)).andExpect(jsonPath("sound").value(true));
        mvc.perform(body(request(patch("/api/users/me"),mine),Map.of("name","박애란"))).andExpect(status().isOk()).andExpect(jsonPath("name").value("박애란"));
        mvc.perform(body(request(post("/api/users/me/password"),mine),Map.of("currentPassword","wrong-password","newPassword","New-password-456"))).andExpect(status().isBadRequest());
        mvc.perform(body(request(post("/api/users/me/password"),mine),Map.of("currentPassword",PASSWORD,"newPassword","New-password-456"))).andExpect(status().isOk());
        mvc.perform(request(get("/api/notifications"),null)).andExpect(status().isUnauthorized());
    }    @Test void feedbackIsPreservedClearedValidatedAndAuditedEvenWithoutLevelChange() throws Exception {
        var f=fixture(AiPolicy.HINT_ONLY);submit(f.id(),f.token());String path=root(f)+"/participants/"+f.pid()+"/rubric-scores";
        mvc.perform(body(request(put(path+"/1"),teacher),Map.of("level",3,"feedback","  교사 근거  "))).andExpect(jsonPath("criteria[0].teacher.feedback").value("  교사 근거  "));
        mvc.perform(body(request(put(path+"/1"),teacher),Map.of("level",4))).andExpect(jsonPath("criteria[0].teacher.feedback").value("  교사 근거  "));
        mvc.perform(body(request(put(path+"/1"),teacher),Map.of("feedback","후속 근거"))).andExpect(jsonPath("criteria[0].teacher.level").value(4));
        mvc.perform(body(request(put(path+"/1"),teacher),Map.of("feedback",""))).andExpect(jsonPath("criteria[0].teacher.feedback").value(""));
        mvc.perform(body(request(put(path+"/1"),teacher),Map.of("feedback",""))).andExpect(status().isOk());
        mvc.perform(request(get(path+"/history"),teacher)).andExpect(jsonPath("length()").value(4))
            .andExpect(jsonPath("[2].previousFeedback").value("  교사 근거  ")).andExpect(jsonPath("[2].feedback").value("후속 근거"))
            .andExpect(jsonPath("[3].feedback").value(""));
        mvc.perform(body(request(put(path+"/1"),teacher),Map.of("level",4,"feedback","가".repeat(2000)))).andExpect(status().isOk());
        mvc.perform(body(request(put(path+"/1"),teacher),Map.of("level",4,"feedback","가".repeat(2001)))).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        var stranger=newTeacher();mvc.perform(request(get(path+"/history"),stranger)).andExpect(status().isNotFound());
    }
    @Test void confirmationReportsMissingCriteriaRollsBackAndAutoAcceptedAiHasNoAdjustment() throws Exception {
        var f=fixture(AiPolicy.HINT_ONLY);ask(f,f.q1(),"feedback-ai","광합성의 개념을 설명해줘");submit(f.id(),f.token());as(teacher);
        grading.saveRubric(f.id(),new AssessmentModels.RubricInput(List.of(
            new AssessmentModels.CriterionInput("AI 기준",50,"",null,AssessmentModels.Indicator.PURPOSE),
            new AssessmentModels.CriterionInput("교사 전용 기준",50,"",null,AssessmentModels.Indicator.MANUAL))));
        String path=root(f)+"/participants/"+f.pid();
        mvc.perform(body(request(post(path+"/grade/confirm"),teacher),Map.of("acceptAiForMissing",true))).andExpect(status().isConflict())
            .andExpect(jsonPath("code").value("GRADING_INCOMPLETE")).andExpect(jsonPath("missingCriteria[0].criterionId").value(2)).andExpect(jsonPath("missingCriteria[0].name").value("교사 전용 기준"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM grading_scores WHERE participant_id=?",Integer.class,f.pid())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM grading_score_changes WHERE participant_id=?",Integer.class,f.pid())).isZero();
        mvc.perform(body(request(put(path+"/rubric-scores/2"),teacher),Map.of("level",3,"feedback","수동 근거"))).andExpect(status().isOk());
        mvc.perform(body(request(post(path+"/grade/confirm"),teacher),Map.of("acceptAiForMissing",true))).andExpect(status().isOk())
            .andExpect(jsonPath("status").value("CONFIRMED")).andExpect(jsonPath("criteria[0].teacher.feedback").value(""))
            .andExpect(jsonPath("criteria[0].adjusted").value(false));
        mvc.perform(body(request(put(path+"/rubric-scores/2"),teacher),Map.of("feedback","변경된 수동 근거"))).andExpect(jsonPath("status").value("DRAFT"));
    }
    @Test void reportsFreezeActualRubricFeedbackAndFallBackToAiReasonInBothFormats() throws Exception {
        var f=fixture(AiPolicy.HINT_ONLY);ask(f,f.q1(),"report-feedback-ai","광합성의 개념을 설명해줘");submit(f.id(),f.token());as(teacher);
        grading.saveScore(f.id(),f.pid(),1,new AssessmentModels.ScoreInput(3,"교사근거우선표식"));
        var scores=grading.scores(f.id(),f.pid());String aiReason=scores.criteria().get(1).ai().reason();
        var first=reports.teacherGenerate(f.id(),f.pid());assertThat(first.content().grading().criteria().get(0).feedback()).isEqualTo("교사근거우선표식");
        String pdfText;try(var doc=org.apache.pdfbox.Loader.loadPDF(pdf.render(first,false))){pdfText=new org.apache.pdfbox.text.PDFTextStripper().getText(doc);}
        var document=kr.dogfoot.hwplib.reader.HWPReader.fromInputStream(new java.io.ByteArrayInputStream(hwp.exportReport(first,false)));
        String hwpText=kr.dogfoot.hwplib.tool.textextractor.TextExtractor.extract(document,kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText);
        for(String text:List.of(pdfText,hwpText)){assertThat(text).contains("교사근거우선표식","AI 활용 목적의 적절성","11.25 / 15");assertThat(text.replaceAll("\\s+","")).contains(aiReason.replaceAll("\\s+",""));}
        grading.saveScore(f.id(),f.pid(),1,new AssessmentModels.ScoreInput(3,"후속근거표식"));
        var next=reports.teacherGenerate(f.id(),f.pid());assertThat(next.version()).isEqualTo(first.version()+1);
        assertThat(reports.teacherReport(f.id(),f.pid(),first.version()).content().grading().criteria().get(0).feedback()).isEqualTo("교사근거우선표식");
        grading.saveScore(f.id(),f.pid(),1,new AssessmentModels.ScoreInput(3,""));
        var fallback=reports.teacherGenerate(f.id(),f.pid());
        assertThat(new ReportLayout().buildDocument(fallback,false).blocks().toString()).contains(scores.criteria().get(0).ai().reason()).doesNotContain("교사근거우선표식","후속근거표식");
    }
}
