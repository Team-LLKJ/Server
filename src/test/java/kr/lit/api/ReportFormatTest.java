package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.ReportModels.*;
import static org.assertj.core.api.Assertions.*;

class ReportFormatTest {
    final ObjectMapper json=new ObjectMapper();
    UsageReport sample(boolean reviewed) throws Exception {
        var src=json.readTree("""
            {"answers":[{"question":{"id":"private-question-uuid","position":1,"title":"학교 급식 개선","content":"잔반 감소 방안을 설명하세요."},"answer":{"content":"<p>학생이 배식량을 선택하도록 제안합니다.</p>"}}],
            "conversations":[{"requestId":"private-request-uuid","questionId":"private-question-uuid","questionText":"대화원문옵션표식","answerText":"배식량 선택제","state":"COMPLETED","createdAt":"2026-11-04T05:55:25.895060Z"}],
            "learningEvents":[{"type":"ANSWER_SAVED","questionId":"private-question-uuid","createdAt":"2026-11-04T05:55:25.895060Z","details":{"source":"DIRECT"}},{"type":"DEVTOOLS_OPEN","createdAt":"2026-11-04T05:56:25Z"}]}
            """);
        var scores=List.of(16,12,16,16,12);var ratings=new ArrayList<Rating>();int i=0;for(var c:Criterion.values())ratings.add(new Rating(c,scores.get(i++),""));
        var review=reviewed?new TeacherReview(1,Compliance.COMPLIANT,ratings,"근거를 바탕으로 작성했습니다.","private-teacher-uuid",Instant.parse("2026-11-04T05:55:25Z"),20):null;
        var analysis=new Analysis("KEYWORD_RULES_V1","REVIEW_REQUIRED",List.of(new Classification("private-request-uuid","private-question-uuid",List.of("UNCLASSIFIED"),List.of(),"대화원문옵션표식","RULE_MATCH_REVIEW_REQUIRED")),List.of(),List.of());
        return new UsageReport("private-report-uuid",1,Instant.parse("2026-11-04T05:55:25Z"),new ReportContent("private-evaluation-uuid","급식 개선 평가","private-participant-uuid","학생",Instant.parse("2026-11-04T05:55:25Z"),"private-hash",src,analysis,review,"provisional",List.of()));
    }
    @Test void bothFormatsShareSectionsKoreanLabelsScoresAndPrivacy() throws Exception {
        var report=sample(true);String pdfText;
        try(var pdf=Loader.loadPDF(new ReportPdf().render(report,false))){pdfText=new PDFTextStripper().getText(pdf);boolean logo=false;for(var name:pdf.getPage(0).getResources().getXObjectNames())if(pdf.getPage(0).getResources().getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image){assertThat(image.getWidth()).isEqualTo(1774);assertThat(image.getHeight()).isEqualTo(887);logo=true;}assertThat(logo).isTrue();}
        var hwp=HWPReader.fromInputStream(new ByteArrayInputStream(new ReportHwpService().exportReport(report,false)));
        assertThat(hwp.getBinData().getEmbeddedBinaryDataList().get(0).getData()).isEqualTo(ReportBrand.getLogoBytes());
        String hwpText=TextExtractor.extract(hwp,TextExtractMethod.InsertControlTextBetweenParagraphText);
        String qa=System.getProperty("lit.report-format.qa-dir");
        if(qa!=null){var dir=java.nio.file.Path.of(qa);java.nio.file.Files.createDirectories(dir);byte[] bytes=new ReportPdf().render(report,false);java.nio.file.Files.write(dir.resolve("report-template.pdf"),bytes);java.nio.file.Files.write(dir.resolve("report-template.hwp"),new ReportHwpService().exportReport(report,false));try(var pdf=Loader.loadPDF(bytes)){var renderer=new org.apache.pdfbox.rendering.PDFRenderer(pdf);for(int page=0;page<pdf.getNumberOfPages();page++)javax.imageio.ImageIO.write(renderer.renderImageWithDPI(page,100),"PNG",dir.resolve("report-template-page-"+(page+1)+".png").toFile());}}
        for(String text:List.of(pdfText,hwpText))assertThat(text).contains("AI 활용 학습 보고서","2026. 11. 04 14:55","72 / 100점","1. 종합 평가","2. 평가 기준별 루브릭","3. AI 활용 분석","4. 활동 및 경고 기록","5. 문항별 제출 답안","개발자 도구 열림","직접 작성","미분류","문항 1")
            .doesNotContain("private-","COMPLETED","DIRECT","UNCLASSIFIED","KEYWORD_RULES_V1","REVIEW_REQUIRED","[미입력]","대화원문옵션표식","부록.","2026-11-04T");
    }
    @Test void ungradedRemainsUngradedAndRawAppendixStartsOnNewPage() throws Exception {
        var report=sample(false);
        try(var doc=Loader.loadPDF(new ReportPdf().render(report,true))){
            var stripper=new PDFTextStripper();var all=stripper.getText(doc);assertThat(all).contains("미채점","대화원문옵션표식");
            stripper.setStartPage(1);stripper.setEndPage(1);assertThat(stripper.getText(doc)).doesNotContain("부록.","대화원문옵션표식");
            stripper.setStartPage(doc.getNumberOfPages());stripper.setEndPage(doc.getNumberOfPages());assertThat(stripper.getText(doc)).contains("부록. AI 대화 기록","대화원문옵션표식");
        }
        var hwp=HWPReader.fromInputStream(new ByteArrayInputStream(new ReportHwpService().exportReport(report,true)));
        var section=hwp.getBodyText().getSectionList().get(0);boolean breakFound=false;
        for(int i=0;i<section.getParagraphCount();i++)if(section.getParagraph(i).getHeader().getDivideSort().isDividePage())breakFound=true;
        assertThat(breakFound).isTrue();
    }
    @Test void analysisTablesPreserveLongFeedbackAndAppendixAcrossPages() throws Exception {
        var original=sample(true);var c=original.content();String reason="학생의 구체적인 판단 근거입니다. ".repeat(110)+"긴근거마지막표식";
        var grading=new GradingSnapshot("DRAFT",List.of(new GradingCriterion(1,"기준",100,75.0,true,reason,null)));
        var report=new UsageReport(original.id(),original.version(),original.createdAt(),new ReportContent(c.evaluationId(),c.title(),c.participantId(),c.nickname(),c.submittedAt(),c.submissionHash(),c.submission(),c.analysis(),c.teacherReview(),c.rubricStatus(),c.limitations(),grading));
        var blocks=new ReportLayout().buildDocument(report,true).blocks();
        for(int i=0;i<blocks.size()-1;i++)if(blocks.get(i) instanceof ReportLayout.Text text&&Set.of("1. 종합 평가","3. AI 활용 분석","4. 활동 및 경고 기록").contains(text.value()))assertThat(blocks.get(i+1)).isInstanceOf(ReportLayout.Table.class);
        try(var pdf=Loader.loadPDF(new ReportPdf().render(report,true))){assertThat(pdf.getNumberOfPages()).isGreaterThan(2);assertThat(new PDFTextStripper().getText(pdf)).contains("긴근거마지막표식","대화원문옵션표식");}
        var hwp=HWPReader.fromInputStream(new ByteArrayInputStream(new ReportHwpService().exportReport(report,true)));
        assertThat(TextExtractor.extract(hwp,TextExtractMethod.InsertControlTextBetweenParagraphText)).contains("긴근거마지막표식","대화원문옵션표식");
        var section=hwp.getBodyText().getSectionList().get(0);int tables=0;
        for(int i=0;i<section.getParagraphCount();i++){var controls=section.getParagraph(i).getControlList();if(controls!=null)for(var control:controls)if(control instanceof kr.dogfoot.hwplib.object.bodytext.control.ControlTable table){tables++;assertThat(table.getTable().getProperty().getDivideAtPageBoundary()).isEqualTo(kr.dogfoot.hwplib.object.bodytext.control.table.DivideAtPageBoundary.Divide);}}
        assertThat(tables).isEqualTo(6);
    }
}
