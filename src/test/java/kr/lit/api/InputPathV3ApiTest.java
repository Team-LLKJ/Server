package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static kr.lit.api.ProcessGradingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InputPathV3ApiTest extends AuthTestSupport {
    @Autowired GradingService grading;
    @Autowired ProcessGradingService process;
    @Autowired ProcessGradingRepository repository;
    @Autowired ProcessGradingWorker worker;
    @Autowired ReportService reports;
    @Autowired ReportPdf pdf;
    @org.springframework.test.context.bean.override.mockito.MockitoBean ProcessGradingAiClient ai;
    record Fixture(String id,String pid,String token,String qid){}
    Fixture createFixture(){
        as(teacher);var evaluation=evaluations.create("새 평가 알고리즘",60);String qid=work.configure(evaluation.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","학교 조건을 고려한 해결책을 설명하세요.")))).questions().get(0).id();
        process.savePolicy(evaluation.id(),new PolicyRequest(0,"학교 조건에 맞는 해결","AI 사용 후 검토",NoAiPolicy.USE_RECORDED_EVIDENCE,List.of(new QuestionWeight(qid,new BigDecimal("100")))));
        var participant=evaluations.join(evaluation.pin(),"학생",null);evaluations.start(evaluation.id());return new Fixture(evaluation.id(),participant.participantId(),participant.participantToken(),qid);
    }
    @Test void replaysMixedPathsDeletesAndFormatPreservationAndFreezesAtSubmission()throws Exception {
        var f=createFixture();var turn=work.reserve(f.id(),f.token(),f.qid(),new Ask("path-ai","개념을 설명해줘"),"stub","test");work.finish(turn,"AI",null);
        var operations=List.of(new InputOperation(InputOperationType.DIRECT,0,0,"직접 ",null),new InputOperation(InputOperationType.DIRECT,3,3,"붙여 ",null),new InputOperation(InputOperationType.AI_INSERT,6,6,"AI",turn.turn().id()));
        work.save(f.id(),f.token(),f.qid(),new SaveAnswer("직접 붙여 AI",0,AnswerSource.DIRECT,null,operations));
        var path=work.getInputPath(f.id(),f.token(),f.qid());assertThat(path.status()).isEqualTo("COMPLETE");assertThat(path.shares().stream().mapToLong(InputPathAnalyzer.Share::characters).sum()).isEqualTo(6);assertThat(path.shares().stream().mapToDouble(InputPathAnalyzer.Share::percentage).sum()).isEqualTo(100);
        work.save(f.id(),f.token(),f.qid(),new SaveAnswer("직접  AI",1,AnswerSource.DIRECT,null,List.of(new InputOperation(InputOperationType.DELETE,3,5,"",null))));
        work.save(f.id(),f.token(),f.qid(),new SaveAnswer("<p><strong>직접</strong>  AI</p>",2,AnswerSource.DIRECT,null,List.of(new InputOperation(InputOperationType.FORMAT,0,6,"",null))));
        path=work.getInputPath(f.id(),f.token(),f.qid());assertThat(path.shares().stream().filter(s->s.type()==InputPathAnalyzer.PathType.PASTE).findFirst().orElseThrow().characters()).isZero();assertThat(path.shares().stream().filter(s->s.type()==InputPathAnalyzer.PathType.AI_INSERT).findFirst().orElseThrow().percentage()).isEqualTo(50);
        var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());as(teacher);var job=process.getRuns(f.id(),f.pid()).get(0);var input=repository.getInput(job.id());
        assertThat(input.analysisScope().answerVersions()).containsExactly(new AnswerVersion(f.qid(),3));assertThat(input.analysisScope().conversationIds()).containsExactly(turn.turn().id());assertThat(input.inputPaths().questions().get(0).shares()).isEqualTo(path.shares());
        db.update("UPDATE answers SET content='강제변경',version=4 WHERE participant_id=?",f.pid());assertThat(repository.getInput(job.id()).inputPaths().questions().get(0).answerVersion()).isEqualTo(3);
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/input-paths"),newTeacher())).andExpect(status().isNotFound());
    }
    @Test void invalidTraceAndForeignAiReferenceRollbackTheWholeSave()throws Exception {
        var f=createFixture();var foreign=createFixture();var turn=work.reserve(foreign.id(),foreign.token(),foreign.qid(),new Ask("foreign","개념 설명"),"stub","test");work.finish(turn,"외부문구",null);
        for(var operation:List.of(new InputOperation(InputOperationType.AI_INSERT,0,0,"외부문구",turn.turn().id()),new InputOperation(InputOperationType.DIRECT,0,1,"다름",null))){
            assertThatThrownBy(()->work.save(f.id(),f.token(),f.qid(),new SaveAnswer("외부문구",0,AnswerSource.DIRECT,null,List.of(operation)))).isInstanceOf(ApiError.Failure.class);
            assertThat(work.getAnswer(f.id(),f.token(),f.qid()).version()).isZero();
        }
        var result=mvc.perform(body(put("/api/student/evaluations/"+f.id()+"/questions/"+f.qid()+"/answer").header("X-Participant-Token",f.token()),Map.of("content","직접","expectedVersion",0,"source","DIRECT","inputOperations",List.of(Map.of("type","DIRECT","start",0,"end",0,"text","다른 내용"))))).andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("INPUT_TRACE_MISMATCH");
    }
    @Test void reportedPasteIsRejectedWithoutChangingSavedAnswer()throws Exception {
        var f=createFixture();
        var input=Map.of("content","붙여넣은 답안","expectedVersion",0,"source","DIRECT","inputOperations",List.of(Map.of("type","PASTE","start",0,"end",0,"text","붙여넣은 답안")));
        mvc.perform(body(put("/api/student/evaluations/"+f.id()+"/questions/"+f.qid()+"/answer").header("X-Participant-Token",f.token()),input))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("PASTE_NOT_ALLOWED"));
        assertThat(work.getAnswer(f.id(),f.token(),f.qid()).version()).isZero();
    }
    @Test void legacyDirectSaveAndPasteSignalDoNotBecomeProvenanceProof(){
        var f=createFixture();work.save(f.id(),f.token(),f.qid(),new SaveAnswer("😀 직접",0,AnswerSource.DIRECT,null));
        work.recordClientEvent(f.id(),f.token(),new ClientEvent(ClientEventType.PASTE,java.time.Instant.now(),f.qid(),2,null,0,2,1));
        var path=work.getInputPath(f.id(),f.token(),f.qid());assertThat(path.status()).isEqualTo("PARTIAL");assertThat(path.shares().stream().filter(s->s.type()==InputPathAnalyzer.PathType.UNKNOWN).findFirst().orElseThrow().characters()).isEqualTo(3);
        assertThatThrownBy(()->work.save(f.id(),f.token(),f.qid(),new SaveAnswer("x 직접",1,AnswerSource.DIRECT,null,List.of(new InputOperation(InputOperationType.DIRECT,0,1,"x",null))))).isInstanceOf(ApiError.Failure.class);
    }
    @Test void reportUsesV3SummaryAndKeepsAiAndTeacherResultsSeparate()throws Exception {
        var f=createFixture();work.save(f.id(),f.token(),f.qid(),new SaveAnswer("학생이 학교의 조건과 근거를 확인해 최종 방안을 선택했습니다.",0,AnswerSource.DIRECT,null));var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());as(teacher);var job=process.getRuns(f.id(),f.pid()).get(0);
        org.mockito.Mockito.when(ai.recommendLevels(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{Input input=call.getArgument(0);var recommendations=new ArrayList<Recommendation>();
            var answer=input.sources().stream().filter(s->s.kind().equals("FINAL_ANSWER")).findFirst().orElseThrow();var cite=new Citation(answer.id(),answer.text());
            for(var c:ProcessGradingModels.Criterion.values()){String q=c==ProcessGradingModels.Criterion.TRANSPARENCY?GLOBAL:f.qid();recommendations.add(new Recommendation(q,c,4,"기존 간략 문구", "추가 확인이 필요합니다.",List.of(new Condition(c.name()+"_L4",List.of(cite))),List.of(),List.of()));}
            return V3GradingTestData.explain(input,new AiOutput(new ProcessGradingModels.Compliance("CHECK","규정 확인이 필요합니다.",List.of(cite),List.of()),recommendations));});
        worker.processJob(job.id());assertThat(process.getRun(f.id(),f.pid(),job.id()).status()).isEqualTo("DONE");
        process.saveReview(f.id(),f.pid(),job.id(),new ReviewRequest(0,List.of(new LevelOverride(f.qid(),ProcessGradingModels.Criterion.CONTRIBUTION,5,"교사가 핵심 주장과 선택의 근거를 확인했습니다.")),"CONFIRMED_OK","교사가 기록을 검토했습니다."));
        var report=reports.teacherGenerate(f.id(),f.pid());assertThat(report.content().grading().rubricVersion()).isEqualTo(RUBRIC_VERSION);assertThat(report.content().grading().summary()).isEqualTo(V3GradingTestData.SUMMARY);assertThat(report.content().grading().aiRecommendation().cells().get(3).level()).isEqualTo(4);assertThat(report.content().grading().teacherReview().overrides().get(0).level()).isEqualTo(5);
        byte[] hwp=new ReportHwpService().exportReport(report,false);
        assertThat(kr.dogfoot.hwplib.reader.HWPReader.fromInputStream(new java.io.ByteArrayInputStream(hwp)).getBodyText().getSectionList()).isNotEmpty();
        String previewDirectory=System.getProperty("lit.v3.preview-dir");
        if(previewDirectory!=null){var output=java.nio.file.Path.of(previewDirectory);java.nio.file.Files.createDirectories(output);java.nio.file.Files.write(output.resolve("evaluation-v3.pdf"),pdf.render(report,false));java.nio.file.Files.write(output.resolve("evaluation-v3.hwp"),hwp);java.nio.file.Files.writeString(output.resolve("report.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));}
        try(var document=org.apache.pdfbox.Loader.loadPDF(pdf.render(report,false))){var text=new org.apache.pdfbox.text.PDFTextStripper().getText(document);assertThat(text).contains("학생은 과제의 해결 방향","가점 근거","확인 필요","개선 방향","입력 경로");}
    }
}
