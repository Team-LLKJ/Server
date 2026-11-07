package kr.lit.api;

import kr.lit.auth.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class ParticipantSessionService {
    private final ParticipantAccess access;private final ParticipantSessionRepository repository;private final ObjectMapper json;
    public ParticipantSessionService(ParticipantAccess access,ParticipantSessionRepository repository,ObjectMapper json){this.access=access;this.repository=repository;this.json=json;}
    public record PolicyStatus(boolean policyAccepted,Instant policyAcceptedAt,String policyVersion) {}
    public record Session(String evaluationId,String participantId,String nickname,String title,String pin,String evaluationState,String status,boolean readOnly,boolean submitted,String guidelines,PolicyStatus policy) {}
    private String getVersion(ParticipantSessionRepository.State state){try{return AuthService.hash(json.writeValueAsString(List.of(state.guidelines(),state.criteria(),state.antiCheat(),state.aiPolicy())));}catch(Exception ex){throw new IllegalStateException(ex);}}
    public PolicyStatus getPolicyStatus(String id,String token){var guest=access.authenticate(id,token);return getPolicyStatus(guest.participantId(),repository.getState(id,guest.participantId()));}
    private PolicyStatus getPolicyStatus(String pid,ParticipantSessionRepository.State state){var version=getVersion(state);var accepted=repository.getAcceptance(pid,version);return new PolicyStatus(accepted.isPresent(),accepted.orElse(null),version);}
    public Session getSession(String id,String token){
        var guest=access.authenticate(id,token);var state=repository.getState(id,guest.participantId());
        String status=state.submitted()?"SUBMITTED":state.state().equals("ENDED")?"ENDED":state.expiresAt()!=null&&!state.expiresAt().isAfter(Instant.now())?"EXPIRED":state.state().equals("WAITING")?"WAITING":"IN_PROGRESS";
        return new Session(id,guest.participantId(),state.nickname(),state.title(),state.pin(),state.state(),status,!status.equals("IN_PROGRESS"),state.submitted(),state.guidelines(),getPolicyStatus(guest.participantId(),state));
    }
    public PolicyStatus acceptPolicy(String id,String token,String version){
        var guest=access.authenticate(id,token);var state=repository.getState(id,guest.participantId());var current=getPolicyStatus(guest.participantId(),state);
        if(!current.policyVersion().equals(version))throw new ApiError.Failure(HttpStatus.CONFLICT,"POLICY_VERSION_CONFLICT","변경된 평가 안내를 다시 확인해주세요.");
        if(current.policyAccepted())return current;
        if(state.submitted()||state.state().equals("ENDED")||state.expiresAt()!=null&&!state.expiresAt().isAfter(Instant.now()))throw new ApiError.Failure(HttpStatus.CONFLICT,"POLICY_ACCEPTANCE_CLOSED","제출·종료 후에는 안내 동의를 새로 기록할 수 없습니다.");
        repository.saveAcceptance(id,guest.participantId(),version);return getPolicyStatus(guest.participantId(),state);
    }
}
