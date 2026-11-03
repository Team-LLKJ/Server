package kr.lit.auth;
import kr.lit.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.lit.auth.AuthModels.*;
@Service
@Transactional
public class NicknameService {
    private final NicknameRepository repository;
    public NicknameService(NicknameRepository repository) {this.repository=repository;}
    public void updateNickname(Principal principal,String nickname) {
        String value=nickname==null?"":nickname.strip();
        if(value.isEmpty()||value.length()>50)throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","닉네임은 1~50자여야 합니다.");
        if(!repository.lockAccount(principal.userId())||!repository.existsSession(principal.userId(),principal.sessionId()))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","다시 로그인해주세요.");
        repository.updateNickname(principal.userId(),value);
    }
}
