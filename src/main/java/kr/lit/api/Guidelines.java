package kr.lit.api;

import org.springframework.http.HttpStatus;

final class Guidelines {
    private Guidelines() {}
    static String resolve(String evaluationContent,String guidelines) {
        if(evaluationContent!=null&&guidelines!=null&&!evaluationContent.equals(guidelines))
            throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","evaluationContent와 guidelines는 같은 값이어야 합니다.");
        return guidelines!=null?guidelines:evaluationContent;
    }
    static String clean(String value) {
        if(value==null)return null;
        if(value.length()>10000)throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","평가 안내는 HTML 포함 10,000자 이하여야 합니다.");
        String cleaned=AnswerHtml.clean(value);
        if(cleaned.length()>10000)throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","정리된 안내 HTML은 10,000자 이하여야 합니다.");
        return cleaned;
    }
}
