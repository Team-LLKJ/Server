package kr.lit.api;

import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static kr.lit.api.ReportModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportHwpApiTest extends AuthTestSupport {
    @Autowired ReportService reports;
    @Autowired ReportHwpService hwp;
    @Autowired AiService ai;
    record Fixture(String id,String pid,String token,String qid) {}
    Fixture createFixture(boolean submitted) {
        as(teacher);var e=evaluations.create("한글 리포트 평가",60);
        var config=work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"첫 번째 문항","분수의 원리를 설명하세요."),new QuestionInput(2,"두 번째 문항","근거를 설명하세요."))));
        var joined=evaluations.join(e.pin(),"학생 한글",null);evaluations.start(e.id());
        String question=config.questions().get(0).id();
        ai.ask(e.id(),joined.participantToken(),question,new Ask("hwp-conversation","대화원문비공개표식 - 개념을 설명하고 자료를 검증해줘"));
        work.save(e.id(),joined.participantToken(),question,new SaveAnswer("<p>분모를 같게 합니다.</p><table><tr><td>한글 셀</td><td>두 번째 셀</td></tr></table><p>마지막 답안</p>",0,AnswerSource.DIRECT,null));
        var f=new Fixture(e.id(),joined.participantId(),joined.participantToken(),question);
        if(submitted)submitFixture(f);
        return f;
    }
    void submitFixture(Fixture f) {var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());}
    String teacherPath(Fixture f) {return "/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/report/hwp";}
    String studentPath(Fixture f) {return "/api/evaluations/"+f.id()+"/report/hwp";}
    String extractText(byte[] bytes) throws Exception {
        assertThat(Arrays.copyOf(bytes,8)).containsExactly((byte)0xd0,(byte)0xcf,(byte)0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,(byte)0x1a,(byte)0xe1);
        var document=HWPReader.fromInputStream(new ByteArrayInputStream(bytes));
        assertThat(document.getSummaryInformation()).isNotNull();
        assertThat(document.getFileHeader().getVersion().getMM()).isEqualTo((short)5);
        return TextExtractor.extract(document,TextExtractMethod.InsertControlTextBetweenParagraphText);
    }
    @Test void teacherAndStudentDownloadRealHwpWithKoreanAnswersAnalysisAndReview() throws Exception {
        var f=createFixture(true);
        reports.saveReview(f.id(),f.pid(),new ReviewInput(0,Compliance.COMPLIANT,Arrays.stream(Criterion.values()).map(c->new Rating(c,3,"교사의 검토 근거")).toList(),"성장 과정 확인"));
        var report=reports.teacherGenerate(f.id(),f.pid());
        byte[] bytes=mvc.perform(request(get(teacherPath(f)).param("version","1"),teacher)).andExpect(status().isOk())
                .andExpect(content().contentType("application/x-hwp")).andExpect(header().string("Content-Disposition","attachment; filename=\"lit-report-"+report.id()+"-v1.hwp\""))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
        assertThat(extractText(bytes)).contains("AI 활용 학습 보고서","한글 리포트 평가","학생 한글","분모를 같게","한글 셀 | 두 번째 셀","마지막 답안","교사의 검토 근거","성장 과정 확인","키워드 기반 분류","활동 및 경고 기록","문항 번호","문항 2","[미작성]")
                .doesNotContain("대화원문비공개표식","<table>","<p>");
        mvc.perform(request(get(teacherPath(f).replace("/students/","/participants/")),teacher)).andExpect(status().isOk());
        byte[] student=mvc.perform(get(studentPath(f)).header("X-Participant-Token",f.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(extractText(student)).isEqualTo(extractText(bytes));
        String qa=System.getProperty("lit.hwp.qa-dir");
        if(qa!=null){Files.createDirectories(Path.of(qa));Files.write(Path.of(qa,"lit-report-sample.hwp"),bytes);}
    }
    @Test void exportsStyledTitleAndRealMetadataAndScoreTables() throws Exception {
        var f=createFixture(true);
        reports.saveReview(f.id(),f.pid(),new ReviewInput(0,Compliance.COMPLIANT,Arrays.stream(Criterion.values()).map(c->new Rating(c,3,"검토 근거")).toList(),"교사 의견"));
        var report=reports.teacherGenerate(f.id(),f.pid());
        var document=HWPReader.fromInputStream(new ByteArrayInputStream(hwp.exportReport(report,false)));
        var section=document.getBodyText().getSectionList().get(0);
        var title=section.getParagraph(0);
        int charId=(int)title.getCharShape().getPositonShapeIdPairList().get(0).getShapeId();
        var titleShape=document.getDocInfo().getCharShapeList().get(charId);
        assertThat(titleShape.getBaseSize()).isEqualTo(1800);
        assertThat(titleShape.getProperty().isBold()).isTrue();
        assertThat(document.getBinData().getEmbeddedBinaryDataList()).hasSize(1);
        assertThat(document.getDocInfo().getBinDataList().get(0).getExtensionForEmbedding()).isEqualTo("png");
        assertThat(document.getDocInfo().getParaShapeList().get(title.getHeader().getParaShapeId()).getProperty1().getAlignment())
                .isEqualTo(kr.dogfoot.hwplib.object.docinfo.parashape.Alignment.Left);
        var tables=new ArrayList<kr.dogfoot.hwplib.object.bodytext.control.ControlTable>();
        for(int i=0;i<section.getParagraphCount();i++) {
            var controls=section.getParagraph(i).getControlList();
            if(controls!=null)for(var control:controls)if(control instanceof kr.dogfoot.hwplib.object.bodytext.control.ControlTable table)tables.add(table);
        }
        assertThat(tables).hasSize(6);
        assertThat(tables.get(0).getTable().getColumnCount()).isEqualTo(2);
        assertThat(tables.get(0).getRowList()).hasSize(3);
        var scores=tables.get(2);
        assertThat(scores.getRowList()).hasSize(6);
        assertThat(scores.getTable().getColumnCount()).isEqualTo(3);
        assertThat(scores.getRowList().get(0).getCellList().get(0).getListHeader().getProperty().isTitleCell()).isTrue();
        assertThat(scores.getTable().getProperty().isAutoRepeatTitleRow()).isTrue();
        var answers=tables.get(5);
        assertThat(answers.getTable().getColumnCount()).isEqualTo(2);
        assertThat(answers.getRowList()).hasSize(3);
        assertThat(answers.getTable().getProperty().isAutoRepeatTitleRow()).isTrue();
        assertThat(extractText(hwp.exportReport(report,false))).contains("KST","1. 종합 평가","2. 평가 기준별 루브릭");
    }
    @Test void exportedVersionsAreFrozenAndConversationOriginalsRequireOptIn() throws Exception {
        var f=createFixture(true);var first=reports.teacherGenerate(f.id(),f.pid());
        reports.saveReview(f.id(),f.pid(),new ReviewInput(0,Compliance.COMPLIANT,Arrays.stream(Criterion.values()).map(c->new Rating(c,4,"추가 검토 내용")).toList(),"두 번째 버전 의견"));
        reports.teacherGenerate(f.id(),f.pid());
        db.update("UPDATE answers SET content='라이브 답안 변경 표식' WHERE participant_id=?",f.pid());
        byte[] old=mvc.perform(request(get(teacherPath(f)).param("version","1"),teacher)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(extractText(old)).contains("미채점","분모를 같게").doesNotContain("두 번째 버전 의견","라이브 답안 변경 표식","대화원문비공개표식");
        byte[] latest=mvc.perform(request(get(teacherPath(f)),teacher)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(extractText(latest)).contains("두 번째 버전 의견");
        byte[] raw=mvc.perform(get(studentPath(f)).param("version","1").param("includeConversations","true").header("X-Participant-Token",f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(extractText(raw)).contains("대화원문비공개표식","부록. AI 대화 기록");
        as(teacher);assertThat(reports.versions(f.id(),f.pid())).hasSize(2);assertThat(first.version()).isEqualTo(1);
    }
    @Test void rejectsForeignAccessMissingReportsAndInvalidVersion() throws Exception {
        var f=createFixture(true);reports.teacherGenerate(f.id(),f.pid());var other=newTeacher();
        mvc.perform(get(teacherPath(f))).andExpect(status().isUnauthorized());
        mvc.perform(request(get(teacherPath(f)),other)).andExpect(status().isNotFound());
        mvc.perform(get(studentPath(f))).andExpect(status().isUnauthorized());
        var foreign=createFixture(false);
        mvc.perform(get(studentPath(f)).header("X-Participant-Token",foreign.token())).andExpect(status().isUnauthorized());
        mvc.perform(request(get(teacherPath(foreign)),teacher)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("REPORT_NOT_FOUND"));
        mvc.perform(get(studentPath(foreign)).header("X-Participant-Token",foreign.token())).andExpect(status().isNotFound());
        mvc.perform(request(get(teacherPath(f)).param("version","0"),teacher)).andExpect(status().isBadRequest());
        mvc.perform(request(get(teacherPath(f)).param("version","999"),teacher)).andExpect(status().isNotFound());
        mvc.perform(get(studentPath(f)).param("version","-1").header("X-Participant-Token",f.token())).andExpect(status().isBadRequest());
    }
    @Test void hwpAliasesAndLongAnswersPreserveTextAndDoNotCreateNewReportVersions() throws Exception {
        var f=createFixture(false);
        String longAnswer="긴 한글 답안과 근거를 설명합니다. ".repeat(3000)+"마지막문장표식";
        work.save(f.id(),f.token(),f.qid(),new SaveAnswer(longAnswer,1,AnswerSource.DIRECT,null));submitFixture(f);
        var report=reports.teacherGenerate(f.id(),f.pid());
        String path=teacherPath(f).replace("/report/hwp","/report.hwp");
        byte[] bytes=mvc.perform(request(get(path),teacher)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(extractText(bytes)).contains("긴 한글 답안","마지막문장표식");
        mvc.perform(get(studentPath(f).replace("/report/hwp","/report.hwp")).header("X-Participant-Token",f.token())).andExpect(status().isOk());
        as(teacher);assertThat(reports.versions(f.id(),f.pid())).hasSize(1);
        assertThat(report.version()).isEqualTo(1);
    }
}
