package kr.lit.api;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal;
import kr.dogfoot.hwplib.object.bodytext.control.gso.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.ShapeComponentNormal;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.*;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.object.docinfo.bindata.*;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.lineinfo.LineType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.shadowinfo.ShadowType;

/** 원본 PNG를 HWP 내부에 포함하고 제목 앞에 글자처럼 배치합니다. */
final class HwpReportLogo {
    private HwpReportLogo() {}
    private static volatile byte[] whiteLogo;
    /** 일부 한글 뷰어(한컴독스 등)는 PNG 투명 배경을 검게 그리므로 흰 배경에 합친 로고를 넣습니다. */
    static byte[] whiteLogo() {
        if(whiteLogo!=null)return whiteLogo;
        try {
            var source=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(ReportBrand.getLogoBytes()));
            var flat=new java.awt.image.BufferedImage(source.getWidth(),source.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=flat.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,flat.getWidth(),flat.getHeight());g.drawImage(source,0,0,null);g.dispose();
            var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(flat,"png",out);
            return whiteLogo=out.toByteArray();
        } catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
    }
    static void addLogo(HWPFile file,Paragraph paragraph,int titleLength) {
        int index=file.getBinData().getEmbeddedBinaryDataList().size()+1;
        file.getBinData().addNewEmbeddedBinaryData("Bin"+String.format("%04X",index)+".png",whiteLogo(),BinDataCompress.ByStorageDefault);
        var data=new BinData();data.getProperty().setType(BinDataType.Embedding);data.getProperty().setCompress(BinDataCompress.ByStorageDefault);data.getProperty().setState(BinDataState.NotAccess);data.setBinDataID(index);data.setExtensionForEmbedding("png");file.getDocInfo().getBinDataList().add(data);
        // 그림 글자는 제목 바로 앞에 둡니다. addExtendCharForGSO는 문단 끝 글자(13)를 다시 붙이므로 끝 글자를 지우고 그림 글자만 골라 옮깁니다.
        var chars=paragraph.getText().getCharList();
        paragraph.getText().addExtendCharForGSO();
        chars.removeIf(c->c.getCode()==13);
        HWPChar marker=null;for(int i=chars.size()-1;i>=0;i--)if(chars.get(i).getCode()==11){marker=chars.remove(i);break;}
        if(marker==null)throw new IllegalStateException("HWP logo marker missing");
        chars.add(chars.size()-titleLength,marker);chars.add(new HWPCharNormal(13));
        var image=(ControlRectangle)paragraph.addNewGsoControl(GsoControlType.Rectangle);
        var header=image.getHeader();var property=header.getProperty();
        property.setLikeWord(true);property.setApplyLineSpace(true);property.setVertRelTo(VertRelTo.Para);property.setHorzRelTo(HorzRelTo.Para);property.setVertRelativeArrange(RelativeArrange.TopOrLeft);property.setHorzRelativeArrange(RelativeArrange.TopOrLeft);property.setWidthCriterion(WidthCriterion.Absolute);property.setHeightCriterion(HeightCriterion.Absolute);property.setAllowOverlap(false);property.setTextFlowMethod(TextFlowMethod.FitWithText);property.setTextHorzArrange(TextHorzArrange.BothSides);property.setObjectNumberSort(ObjectNumberSort.Figure);
        int width=6400,height=3200;header.setWidth(width);header.setHeight(height);header.setOutterMarginRight(1000);header.setInstanceId(0x4c4954);header.setPreventPageDivide(true);
        var shape=(ShapeComponentNormal)image.getShapeComponent();shape.setWidthAtCreate(width);shape.setHeightAtCreate(height);shape.setWidthAtCurrent(width);shape.setHeightAtCurrent(height);shape.setLocalFileVersion(1);shape.getProperty().setRotateWithImage(true);shape.setRotateXCenter(width/2);shape.setRotateYCenter(height/2);
        shape.createLineInfo();shape.getLineInfo().getProperty().setLineType(LineType.None);shape.getLineInfo().setThickness(0);shape.getLineInfo().setOutlineStyle(kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.lineinfo.OutlineStyle.Normal);
        shape.createFillInfo();var fill=shape.getFillInfo();fill.getType().setImageFill(true);fill.createImageFill();fill.getImageFill().setImageFillType(ImageFillType.FitSize);fill.getImageFill().getPictureInfo().setEffect(PictureEffect.RealPicture);fill.getImageFill().getPictureInfo().setBinItemID(file.getDocInfo().getBinDataList().size());
        shape.createShadowInfo();shape.getShadowInfo().setType(ShadowType.None);shape.setMatrixsNormal();
        var rect=image.getShapeComponentRectangle();rect.setX1(0);rect.setY1(0);rect.setX2(width);rect.setY2(0);rect.setX3(width);rect.setY3(height);rect.setX4(0);rect.setY4(height);
    }
}
