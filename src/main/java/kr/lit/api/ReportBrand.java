package kr.lit.api;

import java.io.IOException;

/** PDF/HWP 공통 브랜드 리소스. 첨부 원본 PNG의 투명도·비율을 유지합니다. */
final class ReportBrand {
    static final int PRIMARY_RGB=0x007BFF;
    static final int PRIMARY_BGR=0xFF7B00;
    static final int TINT_BGR=0xFFF8F2;
    private ReportBrand() {}
    static byte[] getLogoBytes() {
        try(var stream=ReportBrand.class.getResourceAsStream("/report/lit-logo.png")) {
            if(stream==null)throw new IllegalStateException("보고서 로고가 없습니다.");
            return stream.readAllBytes();
        }catch(IOException ex){throw new IllegalStateException("보고서 로고를 읽을 수 없습니다.",ex);}
    }
}
