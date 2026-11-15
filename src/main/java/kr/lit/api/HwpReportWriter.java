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
    private static final int WIDTH=50458, MAX_PARAGRAPH_CHARS=2000;
    private final HWPFile document;
    private final int bodyChar,titleChar,subtitleChar,headingChar,noteChar;
    private final int bodyPara,titlePara,headerNotePara,headingPara,cellPara,subtitlePara;
    private final int cellBorder,headerBorder;
    private boolean first=true;
    private int headingNumber;
    HwpReportWriter(HWPFile document) {
        this.document=document;
        var controls=document.getBodyText().getSectionList().get(0).getParagraph(0).getControlList();
        for(var control:controls)if(control instanceof ControlSectionDefine section){var page=section.getPageDef();page.setPaperWidth(59528);page.setPaperHeight(84189);page.setLeftMargin(4535);page.setRightMargin(4535);page.setTopMargin(5102);page.setBottomMargin(5102);}

        var info=document.getDocInfo();
        for(var faces:List.of(info.getHangulFaceNameList(),info.getEnglishFaceNameList(),info.getHanjaFaceNameList(),info.getJapaneseFaceNameList(),info.getEtcFaceNameList(),info.getSymbolFaceNameList(),info.getUserFaceNameList()))for(var face:faces)face.setName("나눔고딕");
        bodyChar=createCharShape(11,false,0x292929);
        titleChar=createCharShape(18,false,0x292929); // PDF 미리보기와 같게 굵게 하지 않습니다
        subtitleChar=createCharShape(11,false,0x292929);
        headingChar=createCharShape(12,false,0x292929);
        noteChar=createCharShape(9,false,0x666666);
        bodyPara=createParaShape(Alignment.Left,0,500,false);
        titlePara=createParaShape(Alignment.Left,500,300,true);
        headerNotePara=createParaShape(Alignment.Left,0,1000,true);
        headingPara=createParaShape(Alignment.Left,1400,700,true);
        cellPara=createParaShape(Alignment.Left,0,0,false);
        subtitlePara=createParaShape(Alignment.Left,600,500,true); // 머리 구분선과 평가 제목 사이를 띄웁니다
        cellBorder=createBorder(0xFFFFFF);
        headerBorder=createBorder(ReportBrand.TINT_BGR);
        document.getDocInfo().getParaShapeList().get(headerNotePara).setBorderFillId(createRuleBorder(ReportBrand.PRIMARY_BGR));
        document.getDocInfo().getParaShapeList().get(headingPara).setBorderFillId(createRuleBorder(ReportBrand.PRIMARY_BGR));
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
    private int createRuleBorder(long color) {
        var border=document.getDocInfo().getBorderFillList().get(0).clone();
        for(var edge:List.of(border.getLeftBorder(),border.getRightBorder(),border.getTopBorder()))edge.setType(BorderType.None);
        border.getBottomBorder().setType(BorderType.Solid);border.getBottomBorder().setThickness(BorderThickness.MM0_2);border.getBottomBorder().getColor().setValue(color);
        document.getDocInfo().getBorderFillList().add(border);return document.getDocInfo().getBorderFillList().size();
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
    void addReportHeader(String title,String date) {
        var paragraph=addParagraph(title,titleChar,titlePara);
        HwpReportLogo.addLogo(document,paragraph,title.length());
        addText(date,noteChar,headerNotePara);
    }
    void addSectionHeading(String text) {addText(text,headingChar,headingPara);}
    void addPageBreak() {var paragraph=addParagraph("",bodyChar,bodyPara);paragraph.getHeader().getDivideSort().setDividePage(true);}
    void addTitle(String text) {addText(text,titleChar,titlePara);}
    void addSubtitle(String text) {addText(text,subtitleChar,subtitlePara);}
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
        if(rows.isEmpty()||Arrays.stream(widths).sum()!=42520)throw new IllegalArgumentException("Invalid report table");
        widths=widths.clone();int assigned=0;for(int i=0;i<widths.length;i++){widths[i]=i==widths.length-1?WIDTH-assigned:(int)((long)widths[i]*WIDTH/42520);assigned+=widths[i];}
        var anchor=addParagraph("",bodyChar,bodyPara);
        var chars=anchor.getText().getCharList();chars.remove(chars.size()-1);
        anchor.getText().addExtendCharForTable();chars.add(new HWPCharNormal(13));
        var control=(ControlTable)anchor.addNewControl(ControlType.Table);
        var header=control.getHeader();var prop=header.getProperty();
        // 글자처럼 취급한 표는 한글에서 쪽을 넘겨 나뉘지 않아 쪽 끝에 큰 빈칸이 생깁니다. 본문 위치에 자리를 차지하는 표로 두고 쪽 경계에서 나눕니다.
        prop.setLikeWord(false);prop.setApplyLineSpace(false);prop.setVertRelTo(VertRelTo.Para);prop.setHorzRelTo(HorzRelTo.Para);
        prop.setVertRelativeArrange(RelativeArrange.TopOrLeft);prop.setHorzRelativeArrange(RelativeArrange.TopOrLeft);
        prop.setWidthCriterion(WidthCriterion.Absolute);prop.setHeightCriterion(HeightCriterion.Absolute);
        prop.setAllowOverlap(false);prop.setTextFlowMethod(TextFlowMethod.TakePlace);prop.setTextHorzArrange(TextHorzArrange.BothSides);prop.setObjectNumberSort(ObjectNumberSort.Table);
        header.setWidth(WIDTH);header.setHeight(rows.size()*2400L);header.setOutterMarginBottom(500);
        var table=control.getTable();table.setRowCount(rows.size());table.setColumnCount(widths.length);
        table.getProperty().setDivideAtPageBoundary(DivideAtPageBoundary.Divide);table.getProperty().setAutoRepeatTitleRow(firstRowHeader);
        table.setBorderFillId(cellBorder);
        // 칸 안 글자가 테두리에 붙지 않게 표·칸 안쪽 여백을 둡니다(약 2.1mm 좌우, 1.4mm 위아래).
        table.setLeftInnerMargin(600);table.setRightInnerMargin(600);table.setTopInnerMargin(400);table.setBottomInnerMargin(400);
        for(int r=0;r<rows.size();r++) {
            if(rows.get(r).size()!=widths.length)throw new IllegalArgumentException("Invalid report table row");
            table.getCellCountOfRowList().add(widths.length);var row=control.addNewRow();
            for(int c=0;c<widths.length;c++) {
                var cell=row.addNewCell();var cellHeader=cell.getListHeader();cellHeader.setParaCount(1);
                cellHeader.setColIndex(c);cellHeader.setRowIndex(r);cellHeader.setColSpan(1);cellHeader.setRowSpan(1);
                cellHeader.setWidth(widths[c]);cellHeader.setHeight(2400);cellHeader.setTextWidth(widths[c]-1200);
                cellHeader.setLeftMargin(600);cellHeader.setRightMargin(600);cellHeader.setTopMargin(400);cellHeader.setBottomMargin(400);cellHeader.getProperty().setApplyInnerMagin(true);
                boolean shaded=firstRowHeader?r==0:(widths.length==4?c%2==0:c==0);cellHeader.setBorderFillId(shaded?headerBorder:cellBorder);
                cellHeader.getProperty().setTitleCell(firstRowHeader&&r==0);
                cellHeader.getProperty().setLineChange(LineChange.Normal);cellHeader.getProperty().setTextVerticalAlignment(TextVerticalAlignment.Center);
                cellHeader.setFieldName("");
                String value=sanitize(rows.get(r).get(c));var parts=new ArrayList<String>();
                for(String line:value.split("\\R",-1)){if(line.isEmpty())parts.add("");else for(int start=0;start<line.length();){int end=Math.min(start+MAX_PARAGRAPH_CHARS,line.length());if(end<line.length()&&Character.isHighSurrogate(line.charAt(end-1)))end--;parts.add(line.substring(start,end));start=end;}}
                cellHeader.setParaCount(parts.size());
                for(int i=0;i<parts.size();i++){var p=cell.getParagraphList().addNewParagraph();p.createText();p.createCharShape();p.getHeader().setLastInList(i==parts.size()-1);setParagraph(p,parts.get(i),bodyChar,cellPara);}
            }
        }
    }
}
