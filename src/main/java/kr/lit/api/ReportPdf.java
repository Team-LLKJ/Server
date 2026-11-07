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
   var layout=new ReportLayout().buildDocument(report,raw);
   try(var writer=new Writer(document,font)){
    writer.addHeader(layout.title(),layout.date());
    for(var block:layout.blocks()) {
     if(block instanceof ReportLayout.Text text)writer.addText(text.value(),text.style());
     else if(block instanceof ReportLayout.Table table)writer.addTable(table);
     else if(block instanceof ReportLayout.PageBreak)writer.newPage();
    }
   }
   document.save(out);return out.toByteArray();
  }catch(IOException e){throw new IllegalStateException("PDF 생성에 실패했습니다.",e);}
 }
 static String label(Criterion c){return switch(c){case PURPOSE_CLARITY->"활용 목적 명확성";case QUESTION_SPECIFICITY->"질문 구체성";case VERIFICATION->"검증 과정";case REVISION->"답안 수정 과정";case POLICY_COMPLIANCE->"허용 규칙 준수";};}
 static String answerText(String html){
  if(!html.matches("(?s).*<\\s*(?:/?[A-Za-z]|!).*"))return html;
  var body=org.jsoup.Jsoup.parseBodyFragment(html).body();body.select("script,style").remove();
  for(var image:body.select("img"))image.replaceWith(new org.jsoup.nodes.TextNode("[이미지]"));
  for(var row:body.select("tr")){
   var cells=row.children().stream().filter(e->e.tagName().equals("td")||e.tagName().equals("th")).map(e->e.text().isBlank()?"[빈 셀]":e.text()).toList();
   row.before(new org.jsoup.nodes.TextNode(String.join(" | ",cells)+"\n"));row.remove();
  }
  for(var br:body.select("br"))br.replaceWith(new org.jsoup.nodes.TextNode("\n"));
  for(var block:body.select("p,div,li,h1,h2,h3,h4,blockquote,pre"))block.appendText("\n");
  return body.wholeText();
 }
 private static final class Writer implements AutoCloseable {
  private static final float LEFT=45.35f, TOP=51.02f, BOTTOM=51.02f, WIDTH=504.58f;
  private final PDDocument doc;private final PDFont font;private PDPageContentStream stream;private float y;private int page;
  Writer(PDDocument doc,PDFont font)throws IOException{this.doc=doc;this.font=font;newPage();}
  void newPage()throws IOException {
   if(stream!=null)stream.close();var p=new PDPage(PDRectangle.A4);doc.addPage(p);page++;stream=new PDPageContentStream(doc,p);y=p.getMediaBox().getHeight()-TOP;
   draw("LIT  |  "+page,LEFT,28,8,0.4f,0.4f,0.4f);
  }
  private void ensureSpace(float height)throws IOException{if(y-height<BOTTOM)newPage();}
  void addHeader(String title,String date)throws IOException {
   var logo=org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(doc,ReportBrand.getLogoBytes(),"LIT logo");
   stream.drawImage(logo,LEFT,y-32,64,32);draw(title,LEFT+80,y-18,18,0.1f,0.1f,0.1f);y-=36;
   draw(date,LEFT+80,y,9,0.4f,0.4f,0.4f);y-=15;rule(0,123f/255f,1,1.3f);y-=14;
  }
  private void rule(float r,float g,float b,float width)throws IOException{stream.setStrokingColor(r,g,b);stream.setLineWidth(width);stream.moveTo(LEFT,y);stream.lineTo(LEFT+WIDTH,y);stream.stroke();}
  void addText(String value,String style)throws IOException {
   boolean heading=style.equals("heading"), question=style.equals("question");int size=heading?12:style.equals("note")?9:11;
   if(heading){ensureSpace(75);y-=14;}
   else if(question){ensureSpace(65);y-=9;}
   for(String line:wrap(value,size,WIDTH)) {ensureSpace(size*1.55f);draw(line,LEFT,y-size,size,style.equals("note")?0.4f:0.12f,style.equals("note")?0.4f:0.12f,style.equals("note")?0.4f:0.12f);y-=size*1.55f;}
   y-=5;if(heading){rule(0,123f/255f,1,0.5f);y-=7;}
  }
  void addTable(ReportLayout.Table table)throws IOException {
   float[] widths=new float[table.widths().length];for(int c=0;c<widths.length;c++)widths[c]=WIDTH*table.widths()[c]/42520f;
   for(int r=0;r<table.rows().size();r++) {
    var lines=new ArrayList<List<String>>();int max=1;
    for(int c=0;c<widths.length;c++){var wrapped=wrap(table.rows().get(r).get(c),11,widths[c]-12);lines.add(wrapped);max=Math.max(max,wrapped.size());}
    int offset=0;
    while(offset<max) {
     if(y-BOTTOM<29){newPage();if(table.header()&&r>0)drawHeader(table.rows().get(0),widths);}
     int capacity=Math.max(1,(int)((y-BOTTOM-12)/17));int count=Math.min(max-offset,capacity);float height=count*17+12;
     for(int c=0;c<widths.length;c++) {
      boolean shaded=table.header()?r==0:(widths.length==4?c%2==0:c==0);float x=LEFT;for(int i=0;i<c;i++)x+=widths[i];
      drawCell(x,y,widths[c],height,shaded);
      for(int l=0;l<count&&offset+l<lines.get(c).size();l++)draw(lines.get(c).get(offset+l),x+6,y-18-l*17,11,0.12f,0.12f,0.12f);
     }
     y-=height;offset+=count;
     if(offset<max){newPage();if(table.header()&&r>0)drawHeader(table.rows().get(0),widths);}
    }
   }y-=8;
  }
  private void drawHeader(List<String> cells,float[] widths)throws IOException {
   float x=LEFT;for(int c=0;c<widths.length;c++){drawCell(x,y,widths[c],29,true);draw(cells.get(c),x+6,y-18,11,0.12f,0.12f,0.12f);x+=widths[c];}y-=29;
  }
  private void drawCell(float x,float top,float width,float height,boolean shaded)throws IOException {
   stream.setNonStrokingColor(shaded?242f/255f:1f,shaded?248f/255f:1f,1f);stream.addRect(x,top-height,width,height);stream.fill();
   stream.setStrokingColor(0.84f,0.85f,0.87f);stream.setLineWidth(0.4f);stream.addRect(x,top-height,width,height);stream.stroke();
  }
  private List<String> wrap(String value,int size,float width)throws IOException {
   var lines=new ArrayList<String>();
   for(String paragraph:Objects.toString(value,"").split("\\R",-1)) {
    StringBuilder line=new StringBuilder();
    for(int cp:paragraph.codePoints().toArray()) {
     String ch=Character.isISOControl(cp)?" ":new String(Character.toChars(cp));try{font.encode(ch);}catch(IllegalArgumentException ex){ch="?";}
     if(font.getStringWidth(line+ch)/1000*size>width&&line.length()>0){lines.add(line.toString());line.setLength(0);}line.append(ch);
    }lines.add(line.toString());
   }return lines;
  }
  private void draw(String text,float x,float baseline,int size,float r,float g,float b)throws IOException{stream.setNonStrokingColor(r,g,b);stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,baseline);stream.showText(text);stream.endText();}
  public void close()throws IOException{if(stream!=null)stream.close();}
 }
}
