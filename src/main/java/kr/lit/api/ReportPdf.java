package kr.lit.api;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;
import static kr.lit.api.ReportModels.*;

@Component
public class ReportPdf {
 public byte[] render(UsageReport report,boolean raw){
  try(var document=new PDDocument();var fontData=getClass().getResourceAsStream("/fonts/NanumGothic-Regular.ttf")){
   var font=PDType0Font.load(document,fontData);var out=new ByteArrayOutputStream();
   try(var writer=new Writer(document,font)){
    var c=report.content();
    writer.line("LIT AI 활용 리포트",18);writer.line(c.title(),14);
    writer.line("학생: "+c.nickname()+" | 리포트 v"+report.version(),11);
    writer.line("제출: "+c.submittedAt()+" | 생성: "+report.createdAt(),9);
    writer.line("리포트 ID: "+report.id(),9);writer.line("제출 확인 해시: "+c.submissionHash(),8);
    writer.line("검토 상태 및 한계",13);
    for(String limit:c.limitations())writer.line(limit,10);
    writer.line("분류 방식: "+c.analysis().method()+" / "+c.analysis().status(),10);
    writer.line("기록 부족 항목: "+String.join(", ",c.analysis().missingData()),10);
    writer.line("5개 영역 - 교사 검토",13);
    if(c.teacherReview()==null){writer.line("미검토: 영역별 점수 미입력. 미입력은 0점을 의미하지 않습니다.",11);}
    else{
     writer.line("규칙 준수 검토: "+c.teacherReview().compliance()+" / 검토 버전 "+c.teacherReview().version(),10);
     int total=0;
     for(var rating:c.teacherReview().ratings()){
      total+=rating.score();writer.line(label(rating.criterion())+": "+rating.score()+" / 4",11);writer.line("근거: "+rating.evidence(),10);
     }
     writer.line("합계: "+total+" / 20 (교사 입력)",11);writer.line("교사 의견: "+c.teacherReview().comment(),10);
    }
    writer.line("최종 제출 답안",13);
    for(var a:c.submission().path("answers")){
     writer.line(a.path("question").path("title").asText(),12);
     writer.line("문항: "+a.path("question").path("content").asText(),10);
     String answer=a.path("answer").path("content").asText();writer.line("답안: "+(answer.isBlank()?"[미작성]":answer),11);
    }
    writer.line("AI 활용 분류 및 이력 요약",13);
    for(var x:c.analysis().classifications()){
     writer.line("요청 "+x.requestId()+": "+String.join(", ",x.purposes())+" / "+String.join(", ",x.stages()),10);
     // Do not leak original question excerpts when the PDF excludes conversation originals.
     if(raw)writer.line("분류 근거: "+x.evidence(),10);
    }
    writer.line("도구·모델 및 상태",13);
    for(var t:c.submission().path("conversations"))writer.line(t.path("requestId").asText()+" | "+t.path("provider").asText()+" / "+t.path("model").asText()+" | "+t.path("state").asText(),10);
    writer.line("직접 작성·참고·수정 이력",13);
    for(var e:c.submission().path("learningEvents"))if(e.path("type").asText().equals("ANSWER_SAVED")){
     var d=e.path("details");writer.line("문항 "+e.path("questionId").asText()+" / v"+d.path("version").asInt()+" / "+d.path("source").asText()+" / AI 참조 "+d.path("aiRequestId").asText("없음"),9);
    }
    if(raw){writer.line("AI 대화 원문 (공유 시 포함 선택)",13);for(var t:c.submission().path("conversations")){
     writer.line("질문: "+t.path("questionText").asText(),10);writer.line("응답: "+(t.path("answerText").isNull()?"[응답 없음]":t.path("answerText").asText()),10);
    }}else writer.line("AI 대화 원문은 내보내기 설정에 따라 제외했습니다.",10);
    for(String limit:c.analysis().limitations())writer.line(limit,9);
   }
   document.save(out);return out.toByteArray();
  }catch(IOException e){throw new IllegalStateException("PDF 생성에 실패했습니다.",e);}
 }
 private String label(Criterion c){return switch(c){case PURPOSE_CLARITY->"활용 목적 명확성";case QUESTION_SPECIFICITY->"질문 구체성";case VERIFICATION->"검증 과정";case REVISION->"답안 수정 과정";case POLICY_COMPLIANCE->"허용 규칙 준수";};}
 private static final class Writer implements AutoCloseable{
  private final PDDocument doc;private final PDFont font;private PDPageContentStream stream;private float y;private int page;
  Writer(PDDocument doc,PDFont font)throws IOException{this.doc=doc;this.font=font;newPage();}
  private void newPage()throws IOException{if(stream!=null)stream.close();var p=new PDPage(PDRectangle.A4);doc.addPage(p);page++;stream=new PDPageContentStream(doc,p);y=790;
   stream.beginText();stream.setFont(font,8);stream.newLineAtOffset(48,28);stream.showText("LIT | "+page);stream.endText();}
  void line(String value,int size)throws IOException{
   for(String paragraph:Objects.toString(value,"").split("\\R",-1)){
    StringBuilder line=new StringBuilder();
    for(int code:paragraph.codePoints().toArray()){
     String ch=Character.isISOControl(code)?" ":new String(Character.toChars(code));
     try{font.encode(ch);}catch(IllegalArgumentException e){ch="?";}
     if(font.getStringWidth(line+ch)/1000*size>498&&line.length()>0){draw(line.toString(),size);line.setLength(0);}line.append(ch);
    }
    draw(line.toString(),size);
   }y-=4;
  }
  private void draw(String text,int size)throws IOException{if(y<size+52)newPage();stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(48,y);stream.showText(text);stream.endText();y-=size*1.5f;}
  public void close()throws IOException{if(stream!=null)stream.close();}
 }
}
