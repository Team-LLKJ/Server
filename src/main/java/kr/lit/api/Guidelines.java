package kr.lit.api;

import org.springframework.http.HttpStatus;

final class Guidelines {
    private Guidelines() {}
    static String resolve(String evaluationContent,String guidelines) {
        if(evaluationContent!=null&&guidelines!=null&&!evaluationContent.equals(guidelines))
            throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","evaluationContent와 guidelines는 같은 값이어야 합니다.");
        return guidelines!=null?guidelines:evaluationContent;
    }
}
