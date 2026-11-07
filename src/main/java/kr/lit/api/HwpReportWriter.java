package kr.lit.api;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal;
import kr.dogfoot.hwplib.object.bodytext.control.*;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.*;
import kr.dogfoot.hwplib.object.bodytext.control.table.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.*;
import kr.dogfoot.hwplib.object.docinfo.parashape.Alignment;
import kr.dogfoot.hwplib.object.docinfo.borderfill.*;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PatternType;
import java.util.*;

/** HWP 문단·글자 스타일과 실제 표를 구성합니다. DB와 외부 렌더러를 사용하지 않습니다. */
final class HwpReportWriter {
    private static final int WIDTH=42520, MAX_PARAGRAPH_CHARS=2000;
    private final HWPFile document;
    private final int bodyChar,titleChar,subtitleChar,headingChar,noteChar;
    private final int bodyPara,titlePara,headingPara,cellPara;
    private final int cellBorder,headerBorder;
    private boolean first=true;
    private int headingNumber;
    HwpReportWriter(HWPFile document) {
        this.document=document;
        bodyChar=createCharShape(11,false,0x292929);
        titleChar=createCharShape(23,true,0xCC6600);
        subtitleChar=createCharShape(14,true,0x292929);
        headingChar=createCharShape(14,true,0xCC6600);
        noteChar=createCharShape(9,false,0x666666);
        bodyPara=createParaShape(Alignment.Left,0,500,false);
        titlePara=createParaShape(Alignment.Center,500,1000,true);
        headingPara=createParaShape(Alignment.Left,1400,700,true);
        cellPara=createParaShape(Alignment.Left,0,0,false);
        cellBorder=createBorder(0xFFFFFF);
        headerBorder=createBorder(0xF5EFE8);
    }
    private int createCharShape(int points,boolean bold,long color) {
        var shape=document.getDocInfo().getCharShapeList().get(0).clone();
        shape.setBaseSize(points*100);shape.getProperty().setBold(bold);shape.getCharColor().setValue(color);
        document.getDocInfo().getCharShapeList().add(shape);return document.getDocInfo().getCharShapeList().size()-1;
    }
    private int createParaShape(Alignment alignment,int before,int after,boolean keepNext) {
        var shape=document.getDocInfo().getParaShapeList().get(3).clone();
        shape.getProperty1().setAlignment(alignment);shape.getProperty1().setTogetherNextPara(keepNext);
        shape.getProperty1().setProtectPara(false);shape.getProperty1().setProtectLoner(true);
        shape.setIndent(0);shape.setTopParaSpace(before);shape.setBottomParaSpace(after);shape.setLineSpace(160);shape.setLineSpace2(160);
        document.getDocInfo().getParaShapeList().add(shape);return document.getDocInfo().getParaShapeList().size()-1;
    }
    private int createBorder(long background) {
        var border=document.getDocInfo().getBorderFillList().get(0).clone();
        for(var edge:List.of(border.getLeftBorder(),border.getRightBorder(),border.getTopBorder(),border.getBottomBorder())) {
            edge.setType(BorderType.Solid);edge.setThickness(BorderThickness.MM0_12);edge.getColor().setValue(0xD5D5D5);
        }
        border.getFillInfo().getType().setPatternFill(true);border.getFillInfo().createPatternFill();
        var fill=border.getFillInfo().getPatternFill();fill.setPatternType(PatternType.None);fill.getBackColor().setValue(background);fill.getPatternColor().setValue(0);
        document.getDocInfo().getBorderFillList().add(border);return document.getDocInfo().getBorderFillList().size();
    }
    void addTitle(String text) {addText(text,titleChar,titlePara);}
    void addSubtitle(String text) {addText(text,subtitleChar,titlePara);}
    void addHeading(String text) {addText(++headingNumber+". "+text,headingChar,headingPara);}
    void addQuestion(String text) {addText(text,headingChar,headingPara);}
    void addText(String text) {addText(text,bodyChar,bodyPara);}
    void addNote(String text) {addText(text,noteChar,bodyPara);}
    private void addText(String value,int charId,int paraId) {
        String safe=sanitize(value);
        for(String line:safe.split("\\R",-1)) {
            if(line.isEmpty()){addParagraph("",charId,paraId);continue;}
            for(int start=0;start<line.length();) {
                int end=Math.min(start+MAX_PARAGRAPH_CHARS,line.length());
                if(end<line.length()&&Character.isHighSurrogate(line.charAt(end-1)))end--;
                addParagraph(line.substring(start,end),charId,paraId);start=end;
            }
        }
    }
    private String sanitize(String value) {return Objects.toString(value,"").replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]"," ").replace('\t',' ');}
    private Paragraph addParagraph(String text,int charId,int paraId) {
        var section=document.getBodyText().getSectionList().get(0);
        Paragraph paragraph;
        if(first){paragraph=section.getParagraph(0);first=false;}
        else {paragraph=section.addNewParagraph();paragraph.createText();paragraph.createCharShape();}
        setParagraph(paragraph,text,charId,paraId);return paragraph;
    }
    private void setParagraph(Paragraph paragraph,String text,int charId,int paraId) {
        paragraph.getHeader().setParaShapeId(paraId);paragraph.getHeader().setStyleId((short)0);
        var chars=paragraph.getText().getCharList();chars.removeIf(c->c.getCode()==13);
        for(int i=0;i<text.length();i++)chars.add(new HWPCharNormal(text.charAt(i)));
        chars.add(new HWPCharNormal(13));
        paragraph.getCharShape().getPositonShapeIdPairList().clear();paragraph.getCharShape().addParaCharShape(0,charId);
        paragraph.deleteLineSeg();
    }
    /** 짧은 요약 정보는 표에, 긴 자유 서술은 표 밖의 일반 문단에 배치합니다. */
    void addTable(List<List<String>> rows,int[] widths,boolean firstRowHeader) {
        if(rows.isEmpty()||Arrays.stream(widths).sum()!=WIDTH)throw new IllegalArgumentException("Invalid report table");
        var anchor=addParagraph("",bodyChar,bodyPara);
        var chars=anchor.getText().getCharList();chars.remove(chars.size()-1);
        anchor.getText().addExtendCharForTable();chars.add(new HWPCharNormal(13));
        var control=(ControlTable)anchor.addNewControl(ControlType.Table);
        var header=control.getHeader();var prop=header.getProperty();
        prop.setLikeWord(true);prop.setApplyLineSpace(false);prop.setVertRelTo(VertRelTo.Para);prop.setHorzRelTo(HorzRelTo.Para);
        prop.setVertRelativeArrange(RelativeArrange.TopOrLeft);prop.setHorzRelativeArrange(RelativeArrange.TopOrLeft);
        prop.setWidthCriterion(WidthCriterion.Absolute);prop.setHeightCriterion(HeightCriterion.Absolute);
        prop.setAllowOverlap(false);prop.setTextFlowMethod(TextFlowMethod.FitWithText);prop.setTextHorzArrange(TextHorzArrange.BothSides);prop.setObjectNumberSort(ObjectNumberSort.Table);
        header.setWidth(WIDTH);header.setHeight(rows.size()*2400L);header.setOutterMarginBottom(500);
        var table=control.getTable();table.setRowCount(rows.size());table.setColumnCount(widths.length);
        table.getProperty().setDivideAtPageBoundary(DivideAtPageBoundary.DivideByCell);table.getProperty().setAutoRepeatTitleRow(firstRowHeader);
        table.setBorderFillId(cellBorder);
        for(int r=0;r<rows.size();r++) {
            if(rows.get(r).size()!=widths.length)throw new IllegalArgumentException("Invalid report table row");
            table.getCellCountOfRowList().add(widths.length);var row=control.addNewRow();
            for(int c=0;c<widths.length;c++) {
                var cell=row.addNewCell();var cellHeader=cell.getListHeader();cellHeader.setParaCount(1);
                cellHeader.setColIndex(c);cellHeader.setRowIndex(r);cellHeader.setColSpan(1);cellHeader.setRowSpan(1);
                cellHeader.setWidth(widths[c]);cellHeader.setHeight(2400);cellHeader.setTextWidth(widths[c]-1000);
                cellHeader.setLeftMargin(500);cellHeader.setRightMargin(500);cellHeader.setTopMargin(400);cellHeader.setBottomMargin(400);
                boolean shaded=firstRowHeader?r==0:c==0;cellHeader.setBorderFillId(shaded?headerBorder:cellBorder);
                cellHeader.getProperty().setTitleCell(firstRowHeader&&r==0);
                cellHeader.getProperty().setLineChange(LineChange.Normal);cellHeader.getProperty().setTextVerticalAlignment(TextVerticalAlignment.Center);
                cellHeader.setFieldName("");
                var p=cell.getParagraphList().addNewParagraph();p.createText();p.createCharShape();
                p.getHeader().setLastInList(true);setParagraph(p,sanitize(rows.get(r).get(c)).replaceAll("\\R"," "),shaded?subtitleChar:bodyChar,cellPara);
            }
        }
    }
}
