package kr.lit.api;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static kr.lit.api.ReportModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportApiTest extends AuthTestSupport {
 @Autowired ReportService reports;
 @Autowired AiService ai;
 @Autowired ReportPdf pdf;
 @Autowired ReportJobs jobs;
 record Fixture(String id,String pid,String token,String qid){}
 Fixture fixture(boolean submitted){
  as(teacher);var e=evaluations.create("한글 수행평가",60);
  var config=work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"분수의 원리","분수의 덧셈 과정을 설명하세요."))));
  var p=evaluations.join(e.pin(),"학생 한글",null);evaluations.start(e.id());String q=config.questions().get(0).id();
  var turn=ai.ask(e.id(),p.participantToken(),q,new Ask("report-ask","원문전용키워드 - 아이디어와 자료 출처를 검증하고 답안을 수정해줘"));
  work.save(e.id(),p.participantToken(),q,new SaveAnswer("분모를 같게 하여 분자를 더합니다.",0,AnswerSource.AI_REFERENCED,turn.requestId()));
  if(submitted){var preview=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),preview.reviewToken());}
  return new Fixture(e.id(),p.participantId(),p.participantToken(),q);
 }
 String root(Fixture f){return "/api/teacher/evaluations/"+f.id()+"/students/"+f.pid();}
 ReviewInput review(int version){return new ReviewInput(version,Compliance.COMPLIANT,Arrays.stream(Criterion.values()).map(c->new Rating(c,3,"교사가 확인한 과정 근거입니다.")).toList(),"성장 과정이 확인됩니다.");}
 @Test void submittedReportIsIdempotentAndVersionsKeepFrozenReview() throws Exception {
  var f=fixture(true);var first=reports.teacherGenerate(f.id(),f.pid());
  assertThat(first.version()).isEqualTo(1);assertThat(first.content().teacherReview()).isNull();
  assertThat(first.content().analysis().classifications().get(0).purposes()).contains("IDEATION","RESEARCH","VERIFICATION","REVISION");
  assertThat(reports.teacherGenerate(f.id(),f.pid()).id()).isEqualTo(first.id());
  reports.saveReview(f.id(),f.pid(),review(0));var second=reports.teacherGenerate(f.id(),f.pid());
  assertThat(second.version()).isEqualTo(2);assertThat(second.content().teacherReview().ratings()).hasSize(5);
  assertThat(reports.teacherReport(f.id(),f.pid(),1).content().teacherReview()).isNull();
  assertThat(reports.versions(f.id(),f.pid())).hasSize(2);
  mvc.perform(request(body(put(root(f)+"/review"),review(0)),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("REVIEW_VERSION_CONFLICT"));
  as(teacher);var old=second.content().submission().at("/answers/0/answer/content").asText();
  db.update("UPDATE answers SET content='변경된 라이브 데이터' WHERE participant_id=?",f.pid());
  assertThat(reports.teacherReport(f.id(),f.pid(),2).content().submission().at("/answers/0/answer/content").asText()).isEqualTo(old);
 }
 @Test void ownerAndStudentIsolationAndSubmissionRequirement() throws Exception {
  var f=fixture(false);var other=newTeacher();
  mvc.perform(request(get(root(f)),other)).andExpect(status().isNotFound());
  mvc.perform(request(post(root(f)+"/reports"),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("SUBMISSION_REQUIRED"));
  mvc.perform(get(root(f))).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/evaluations/"+f.id()+"/report").header("X-Participant-Token",f.token())).andExpect(status().isForbidden());
  var second=fixture(true);
  mvc.perform(get("/api/evaluations/"+f.id()+"/report").header("X-Participant-Token",second.token())).andExpect(status().isForbidden());
  mvc.perform(request(get("/api/teacher/evaluations/"+second.id()+"/students/"+f.pid()),teacher)).andExpect(status().isNotFound());
  mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students?page=-1"),teacher)).andExpect(status().isBadRequest());
 }
 @Test void studentListAndDraftDetailIncludeDisconnectedAndUnsubmitted() throws Exception {
  var f=fixture(false);evaluations.participant(f.id(),f.token(),true);
  mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students"),teacher))
   .andExpect(status().isOk()).andExpect(jsonPath("items[0].presence").value("LEFT"))
   .andExpect(jsonPath("items[0].submissionStatus").value("NOT_SUBMITTED"));
  as(teacher);var detail=reports.detail(f.id(),f.pid());assertThat(detail.work().path("answers").size()).isEqualTo(1);
  assertThat(detail.analysis().classifications()).hasSize(1);
  evaluations.end(f.id());assertThat(reports.students(f.id(),0,20).items().get(0).submissionStatus()).isEqualTo("SUBMITTED");
 }
 @Test void duplicateRubricAndOutOfRangeScoreRejected() throws Exception {
  var f=fixture(true);var input=new ReviewInput(0,Compliance.COMPLIANT,Collections.nCopies(5,new Rating(Criterion.REVISION,2,"근거")),"");
  mvc.perform(request(body(put(root(f)+"/review"),input),teacher)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_RUBRIC"));
  var ratings=new ArrayList<>(review(0).ratings());ratings.set(0,new Rating(Criterion.PURPOSE_CLARITY,5,"근거"));
  mvc.perform(request(body(put(root(f)+"/review"),new ReviewInput(0,Compliance.COMPLIANT,ratings,"")),teacher)).andExpect(status().isBadRequest());
 }
 @Test void endClosesPendingAiWithoutChangingDraftOrAllowingLateSuccess(){
  var f=fixture(false);as(teacher);var r=work.reserve(f.id(),f.token(),f.qid(),new Ask("pending","설명"),"stub","local-stub");
  evaluations.end(f.id());var closed=work.finish(r,"늦은 응답",null);
  assertThat(closed.state()).isEqualTo("FAILED");assertThat(closed.errorCode()).isEqualTo("EVALUATION_CLOSED");
  assertThat(closed.answerText()).isNull();assertThat(submissionByToken(f.id(),f.token()).snapshot().at("/answers/0/answer/content").asText()).contains("분모");
  evaluations.end(f.id());as(teacher);assertThat(work.teacherEvents(f.id(),f.pid(),0,100).stream().filter(e->e.type().equals("AI_FAILED"))).hasSize(1);
 }
 @Test void pdfHasKoreanAndConversationExportIsOptional() throws Exception {
  var f=fixture(true);reports.saveReview(f.id(),f.pid(),review(0));var report=reports.teacherGenerate(f.id(),f.pid());
  byte[] bytes=mvc.perform(request(get(root(f)+"/report/pdf?version=1"),teacher)).andExpect(status().isOk())
   .andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
  mvc.perform(request(get(root(f).replace("/students/","/participants/")+"/report.pdf?version=1"),teacher)).andExpect(status().isOk());
  try(var doc=Loader.loadPDF(bytes)){
   String qa=System.getProperty("lit.pdf.qa-dir");
   if(qa!=null){var dir=Path.of(qa);Files.createDirectories(dir);Files.write(dir.resolve("lit-report-sample.pdf"),bytes);var renderer=new PDFRenderer(doc);for(int i=0;i<doc.getNumberOfPages();i++)ImageIO.write(renderer.renderImageWithDPI(i,100),"PNG",dir.resolve("lit-report-page-"+(i+1)+".png").toFile());}
   String text=new PDFTextStripper().getText(doc);assertThat(text).contains("한글 수행평가","분모를 같게","교사가 확인한","AI 활용 학습 보고서").doesNotContain("원문전용키워드");
  }
  try(var doc=Loader.loadPDF(pdf.render(report,true))){assertThat(new PDFTextStripper().getText(doc)).contains("원문전용키워드");}
 }
 @Test void backgroundReportAndMissingDataDoNotBecomeZeroScores(){
  var f=fixture(true);clear();jobs.generateSubmittedReports();as(teacher);
  assertThat(reports.teacherReport(f.id(),f.pid(),null).content().teacherReview()).isNull();
 }
 @Test void missingAiAndBlankAnswerAreExplicit(){
  as(teacher);var e=evaluations.create("미작성 평가",60);work.configure(e.id(),new Configure(AiPolicy.DISABLED,List.of(new QuestionInput(1,"문항","설명"))));
  var p=evaluations.join(e.pin(),"미작성",null);evaluations.start(e.id());var preview=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),preview.reviewToken());
  var report=reports.teacherGenerate(e.id(),p.participantId());assertThat(report.content().analysis().missingData()).contains("NO_AI_CONVERSATIONS","NO_ANSWER_DATA");assertThat(report.content().teacherReview()).isNull();
 }
 @Test void longKoreanAnswerAndUnsupportedCharactersDoNotBreakPdf() throws Exception {
  var f=fixture(false);work.save(f.id(),f.token(),f.qid(),new SaveAnswer("긴 한글 답안과 근거를 설명합니다. ".repeat(220)+"😀",1,AnswerSource.DIRECT,null));
  var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());
  try(var doc=Loader.loadPDF(pdf.render(reports.teacherGenerate(f.id(),f.pid()),false))){assertThat(doc.getNumberOfPages()).isGreaterThan(2);assertThat(new PDFTextStripper().getText(doc)).contains("긴 한글 답안");}
 }

 @Test void studentReportApisAreTeacherOnly() throws Exception {
  // 보고서는 교사만 만듭니다(2026-11-14). 참가자 토큰으로는 만들기·조회·파일 모두 403.
  var f=fixture(true);
  for(var call:List.of(post("/api/evaluations/"+f.id()+"/reports"),get("/api/evaluations/"+f.id()+"/report?version=1"),get("/api/evaluations/"+f.id()+"/report/pdf"),
          get("/api/evaluations/"+f.id()+"/report/hwp"),get("/api/evaluations/"+f.id()+"/report.hwp"),get("/api/evaluations/"+f.id()+"/report.pdf")))
   mvc.perform(call.header("X-Participant-Token",f.token())).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("TEACHER_ONLY"));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM usage_reports WHERE participant_id=?",Integer.class,f.pid())).isZero();
  mvc.perform(request(post(root(f)+"/reports"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("version").value(1));
  mvc.perform(request(get(root(f)+"/report?version=99"),teacher)).andExpect(status().isNotFound());
 }

}
