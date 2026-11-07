package kr.lit.api;

import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import kr.dogfoot.hwplib.org.apache.poi.hpsf.*;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static kr.lit.api.ReportModels.*;

/** 저장된 리포트만 입력으로 받는 HWP 5 문서 생성기. DB·외부 변환 서버에 접근하지 않습니다. */
@Service
public class ReportHwpService {
    public byte[] exportReport(UsageReport report,boolean includeConversations) {
        try {
            var document=BlankFileMaker.make();
            var properties=new MutablePropertySet();
            var summarySection=(MutableSection)properties.getFirstSection();
            summarySection.setFormatID(HexFormat.of().parseHex("9fa2b660106111d4b4c6006097c09d8c"));
            summarySection.setCodepage(1200);
            var summary=new SummaryInformation(properties);
            summary.setTitle(report.content().title());summary.setAuthor(report.content().nickname());
            document.setSummaryInformation(summary);
            var writer=new HwpReportWriter(document);
            var layout=new ReportLayout().buildDocument(report,includeConversations);
            writer.addReportHeader(layout.title(),layout.date());
            for(var block:layout.blocks()) {
                if(block instanceof ReportLayout.Text text)switch(text.style()) {
                    case "heading" -> writer.addSectionHeading(text.value());
                    case "question" -> writer.addQuestion(text.value());
                    case "subtitle" -> writer.addSubtitle(text.value());
                    case "note" -> writer.addNote(text.value());
                    default -> writer.addText(text.value());
                }
                else if(block instanceof ReportLayout.Table table)writer.addTable(table.rows(),table.widths(),table.header());
                else if(block instanceof ReportLayout.PageBreak)writer.addPageBreak();
            }
            var output=new ByteArrayOutputStream();HWPWriter.toStream(document,output);return output.toByteArray();
        }catch(Exception ex){throw new IllegalStateException("HWP 리포트 생성에 실패했습니다.",ex);}
    }
    private static String formatDate(java.time.Instant value) {
        return value==null?"미기록":java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'KST'")
                .withZone(java.time.ZoneId.of("Asia/Seoul")).format(value);
    }
}
