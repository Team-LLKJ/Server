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
            var c=report.content();
            writer.addTitle("LIT AI 활용 리포트");writer.addSubtitle(c.title());
            writer.addTable(List.of(List.of("학생",c.nickname()),List.of("리포트 버전","v"+report.version()),
                    List.of("제출 시각",formatDate(c.submittedAt())),List.of("생성 시각",formatDate(report.createdAt()))),new int[]{10520,32000},false);
            writer.addHeading("검토 상태 및 판단 한계");
            for(String limit:c.limitations())writer.addText(limit);
            writer.addText("분류 방식: "+c.analysis().method()+" / "+c.analysis().status());
            writer.addText("기록 부족 항목: "+String.join(", ",c.analysis().missingData()));
            writer.addText("AI 허용 정책: "+c.submission().path("aiPolicy").asText());
            writer.addHeading("5개 영역 - 교사 검토");
            var review=c.teacherReview();
            if(review==null)writer.addText("미검토: 영역별 점수 미입력. 미입력은 0점을 의미하지 않습니다.");
            else {
                writer.addText("규칙 준수 검토: "+review.compliance()+" / 검토 버전 "+review.version());
                var scoreRows=new ArrayList<List<String>>();scoreRows.add(List.of("평가 영역","교사 점수","영역 배점"));
                for(var rating:review.ratings())scoreRows.add(List.of(ReportPdf.label(rating.criterion()),rating.score()==null?"미입력":String.valueOf(rating.score()),String.valueOf(review.maxScore())));
                writer.addTable(scoreRows,new int[]{27520,7500,7500},true);
                int total=0,rated=0;
                for(var rating:review.ratings()) {
                    if(rating.score()!=null){total+=rating.score();rated++;}
                    writer.addText(ReportPdf.label(rating.criterion())+": "+(rating.score()==null?"미입력":rating.score())+" / "+review.maxScore());
                    writer.addText("근거: "+(rating.evidence()==null||rating.evidence().isBlank()?"[미입력]":rating.evidence()));
                }
                writer.addText(rated==5?"합계: "+total+" / "+review.maxScore()*5+" (교사 입력)":"부분합: "+total+" (입력 "+rated+"/5개 영역, 미입력은 0점이 아닙니다.)");
                writer.addText("교사 의견: "+review.comment());
                writer.addText("검토자: "+review.teacherId()+" | 검토 시각: "+formatDate(review.reviewedAt()));
            }
            writer.addHeading("최종 제출 답안");
            for(var answer:c.submission().path("answers")) {
                writer.addQuestion("문항 "+answer.path("question").path("position").asInt()+" · "+answer.path("question").path("title").asText());
                writer.addText("문항: "+ReportPdf.answerText(answer.path("question").path("content").asText()));
                String content=answer.path("answer").path("content").asText();
                writer.addText("답안: "+(content.isBlank()?"[미작성]":ReportPdf.answerText(content)));
            }
            writer.addHeading("AI 활용 분류 및 이력 요약");
            for(var classification:c.analysis().classifications()) {
                writer.addText("요청 "+classification.requestId()+": "+String.join(", ",classification.purposes())+" / "+String.join(", ",classification.stages()));
                if(includeConversations)writer.addText("분류 근거: "+classification.evidence());
            }
            writer.addHeading("도구·모델 및 상태");
            for(var turn:c.submission().path("conversations"))writer.addText(turn.path("requestId").asText()+" | "+turn.path("provider").asText()+" / "+turn.path("model").asText()+" | "+turn.path("state").asText());
            writer.addHeading("직접 작성·참고·수정 이력");
            for(var event:c.submission().path("learningEvents"))if(event.path("type").asText().equals("ANSWER_SAVED")) {
                var detail=event.path("details");
                writer.addText("문항 "+event.path("questionId").asText()+" / v"+detail.path("version").asInt()+" / "+detail.path("source").asText()+" / AI 참조 "+detail.path("aiRequestId").asText("없음"));
            }
            if(includeConversations) {
                writer.addHeading("AI 대화 원문 (포함 선택)");
                for(var turn:c.submission().path("conversations")) {
                    writer.addText("질문: "+turn.path("questionText").asText());
                    writer.addText("응답: "+(turn.path("answerText").isNull()?"[응답 없음]":turn.path("answerText").asText()));
                }
            }else writer.addText("AI 대화 원문은 내보내기 설정에 따라 제외했습니다.");
            for(String limit:c.analysis().limitations())writer.addText(limit);
            writer.addHeading("문서 식별 정보");
            writer.addNote("리포트 ID: "+report.id());writer.addNote("제출 확인 해시: "+c.submissionHash());
            var output=new ByteArrayOutputStream();HWPWriter.toStream(document,output);return output.toByteArray();
        }catch(Exception ex){throw new IllegalStateException("HWP 리포트 생성에 실패했습니다.",ex);}
    }
    private static String formatDate(java.time.Instant value) {
        return value==null?"미기록":java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'KST'")
                .withZone(java.time.ZoneId.of("Asia/Seoul")).format(value);
    }
}
