package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.lit.auth.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;

@Service
@Transactional
public class ParticipantExpulsionService {
    private final EvaluationService evaluations;private final ParticipantExpulsionRepository repository;private final ObjectMapper json;
    public ParticipantExpulsionService(EvaluationService evaluations,ParticipantExpulsionRepository repository,ObjectMapper json){this.evaluations=evaluations;this.repository=repository;this.json=json;}
    public void expelParticipant(String id,String pid){
        var actor=AccountAccess.require(AuthModels.Role.TEACHER);
        var evaluation=evaluations.details(id); // Existing scope validation and evaluation row lock.
        var participant=repository.getParticipant(id,pid).orElseThrow(()->new ApiError.Failure(HttpStatus.NOT_FOUND,"PARTICIPANT_NOT_FOUND","해당 평가의 참가자가 아닙니다."));
        if(participant.expelledAt()!=null)return;
        if(evaluation.state().equals("ENDED")||evaluation.state().equals("STARTED")&&evaluation.expiresAt()!=null&&!evaluation.expiresAt().isAfter(Instant.now()))throw new ApiError.Failure(HttpStatus.CONFLICT,"EVALUATION_ENDED","종료된 평가에서 새로 추방할 수 없습니다.");
        repository.expelParticipant(id,pid);
        for(var turn:repository.getPendingTurns(id,pid)){
            repository.failTurn(turn.id());
            repository.saveEvent(id,pid,turn.questionId(),"AI_FAILED",encode(Map.of("requestId",turn.requestId(),"errorCode","PARTICIPANT_EXPELLED")));
        }
        repository.saveEvent(id,pid,null,"PARTICIPANT_EXPELLED",encode(Map.of("teacherId",actor.userId())));
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
}
