package kr.lit.api;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ParticipantReconnectService {
    private final EvaluationService evaluations;
    private final WorkService work;
    private final ParticipantReconnectRepository repository;
    public ParticipantReconnectService(EvaluationService evaluations,WorkService work,ParticipantReconnectRepository repository) {
        this.evaluations=evaluations;this.work=work;this.repository=repository;
    }
    public record ReconnectResponse(String evaluationId,String participantId,String nickname,
                                    EvaluationService.Lobby lobby,boolean readOnly,WorkModels.Workspace workspace) {}
    public ReconnectResponse reconnectParticipant(String evaluationId,String token) {
        // 평가 행 잠금으로 시작·종료·저장과 직렬화합니다. 만료된 PIN은 복귀 자격 증명이 아닙니다.
        if(token==null||!token.matches("[a-f0-9]{64}"))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","기존 참가자 토큰을 확인해주세요.");
        var participant=repository.findParticipant(evaluationId,token).orElseThrow(()->new ApiError.Failure(
                HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","기존 참가자 토큰을 확인해주세요. PIN·닉네임만으로는 복귀할 수 없습니다."));
        var evaluation=evaluations.details(evaluationId);
        repository.requireNotExpelled(evaluationId,participant.participantId());
        repository.activateParticipant(evaluationId,participant.participantId());
        // 마감이 지났다면 기존 자동 제출 처리를 먼저 수행해 읽기 전용 상태로 복귀시킵니다.
        var lobby=evaluations.participant(evaluationId,token,false);
        WorkModels.Workspace workspace=evaluation.state().equals("WAITING")?null:work.workspace(evaluationId,token);
        return new ReconnectResponse(evaluationId,participant.participantId(),participant.nickname(),lobby,
                workspace==null||workspace.readOnly(),workspace);
    }
}
