package kr.lit.api;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal;
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
    private static final int MAX_PARAGRAPH_CHARS=2000;
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
            var writer=new DocumentWriter(document);
            var c=report.content();
            writer.addText("LIT AI 활용 리포트");writer.addText(c.title());
            writer.addText("학생: "+c.nickname()+" | 리포트 v"+report.version());
            writer.addText("제출: "+c.submittedAt()+" | 생성: "+report.createdAt());
            writer.addText("리포트 ID: "+report.id());writer.addText("제출 확인 해시: "+c.submissionHash());
            writer.addText("검토 상태 및 판단 한계");
            for(String limit:c.limitations())writer.addText(limit);
            writer.addText("분류 방식: "+c.analysis().method()+" / "+c.analysis().status());
            writer.addText("기록 부족 항목: "+String.join(", ",c.analysis().missingData()));
            writer.addText("AI 허용 정책: "+c.submission().path("aiPolicy").asText());
            writer.addText("5개 영역 - 교사 검토");
            var review=c.teacherReview();
            if(review==null)writer.addText("미검토: 영역별 점수 미입력. 미입력은 0점을 의미하지 않습니다.");
            else {
                writer.addText("규칙 준수 검토: "+review.compliance()+" / 검토 버전 "+review.version());
                int total=0,rated=0;
                for(var rating:review.ratings()) {
                    if(rating.score()!=null){total+=rating.score();rated++;}
                    writer.addText(ReportPdf.label(rating.criterion())+": "+(rating.score()==null?"미입력":rating.score())+" / "+review.maxScore());
                    writer.addText("근거: "+(rating.evidence()==null||rating.evidence().isBlank()?"[미입력]":rating.evidence()));
                }
                writer.addText(rated==5?"합계: "+total+" / "+review.maxScore()*5+" (교사 입력)":"부분합: "+total+" (입력 "+rated+"/5개 영역, 미입력은 0점이 아닙니다.)");
                writer.addText("교사 의견: "+review.comment());
                writer.addText("검토자: "+review.teacherId()+" | 검토 시각: "+review.reviewedAt());
            }
            writer.addText("최종 제출 답안");
            for(var answer:c.submission().path("answers")) {
                writer.addText(answer.path("question").path("title").asText());
                writer.addText("문항: "+ReportPdf.answerText(answer.path("question").path("content").asText()));
                String content=answer.path("answer").path("content").asText();
                writer.addText("답안: "+(content.isBlank()?"[미작성]":ReportPdf.answerText(content)));
            }
            writer.addText("AI 활용 분류 및 이력 요약");
            for(var classification:c.analysis().classifications()) {
                writer.addText("요청 "+classification.requestId()+": "+String.join(", ",classification.purposes())+" / "+String.join(", ",classification.stages()));
                if(includeConversations)writer.addText("분류 근거: "+classification.evidence());
            }
            writer.addText("도구·모델 및 상태");
            for(var turn:c.submission().path("conversations"))writer.addText(turn.path("requestId").asText()+" | "+turn.path("provider").asText()+" / "+turn.path("model").asText()+" | "+turn.path("state").asText());
            writer.addText("직접 작성·참고·수정 이력");
            for(var event:c.submission().path("learningEvents"))if(event.path("type").asText().equals("ANSWER_SAVED")) {
                var detail=event.path("details");
                writer.addText("문항 "+event.path("questionId").asText()+" / v"+detail.path("version").asInt()+" / "+detail.path("source").asText()+" / AI 참조 "+detail.path("aiRequestId").asText("없음"));
            }
            if(includeConversations) {
                writer.addText("AI 대화 원문 (포함 선택)");
                for(var turn:c.submission().path("conversations")) {
                    writer.addText("질문: "+turn.path("questionText").asText());
                    writer.addText("응답: "+(turn.path("answerText").isNull()?"[응답 없음]":turn.path("answerText").asText()));
                }
            }else writer.addText("AI 대화 원문은 내보내기 설정에 따라 제외했습니다.");
            for(String limit:c.analysis().limitations())writer.addText(limit);
            var output=new ByteArrayOutputStream();HWPWriter.toStream(document,output);return output.toByteArray();
        }catch(Exception ex){throw new IllegalStateException("HWP 리포트 생성에 실패했습니다.",ex);}
    }
    private static final class DocumentWriter {
        private final HWPFile document;
        private boolean first=true;
        private DocumentWriter(HWPFile document){this.document=document;}
        private void addText(String value) {
            String safe=Objects.toString(value,"").replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]"," ").replace('\t',' ');
            for(String line:safe.split("\\R",-1)) {
                if(line.isEmpty()){addParagraph("");continue;}
                int start=0;
                while(start<line.length()) {
                    int end=Math.min(start+MAX_PARAGRAPH_CHARS,line.length());
                    if(end<line.length()&&Character.isHighSurrogate(line.charAt(end-1)))end--;
                    addParagraph(line.substring(start,end));start=end;
                }
            }
        }
        private void addParagraph(String text) {
            var section=document.getBodyText().getSectionList().get(0);
            Paragraph paragraph;
            if(first){paragraph=section.getParagraph(0);first=false;}
            else {
                paragraph=section.addNewParagraph();
                paragraph.getHeader().setParaShapeId(3);paragraph.getHeader().setStyleId((short)0);
                paragraph.createText();paragraph.createCharShape();paragraph.getCharShape().addParaCharShape(0,0);
            }
            // 첫 문단의 구역·단 정의를 보존하며 UTF-16 코드 단위를 그대로 기록합니다.
            var chars=paragraph.getText().getCharList();chars.removeIf(c->c.getCode()==13);
            for(int i=0;i<text.length();i++)chars.add(new HWPCharNormal(text.charAt(i)));
            chars.add(new HWPCharNormal(13));
            paragraph.deleteLineSeg(); // 한글에서 실제 글꼴·용지에 맞춰 줄 배치를 다시 계산합니다.
        }
    }
}
