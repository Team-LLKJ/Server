package kr.lit.auth;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import kr.lit.api.ApiError;
import static kr.lit.auth.AuthModels.*;

public final class AccountAccess {
    private AccountAccess() {}
    public static Principal current() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !(auth.getPrincipal() instanceof Principal p))
            throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","로그인이 필요합니다.");
        return p;
    }
    public static Principal require(Role role) {
        var p=current();
        if(p.role()!=role) throw new ApiError.Failure(HttpStatus.FORBIDDEN,"ACCESS_DENIED","접근 권한이 없습니다.");
        return p;
    }
    public static void checkTeacherOwner(String owner) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof Principal p && p.role()==Role.TEACHER && !p.userId().equals(owner))
            throw new ApiError.Failure(HttpStatus.NOT_FOUND,"EVALUATION_NOT_FOUND","평가를 찾을 수 없습니다.");
    }
}
