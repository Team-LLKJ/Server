package kr.lit.api;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import static kr.lit.api.WorkModels.*;

@Service
public class AiService {
    private final WorkService work;
    private final AiClient client;
    public AiService(WorkService work,AiClient client) { this.work=work;this.client=client; }
    // Reserve and persist first. Network I/O deliberately runs outside the database transaction.
    public Turn ask(String evaluationId,String token,String questionId,Ask ask) {
        var reservation=work.reserve(evaluationId,token,questionId,ask,client.provider(),client.model());
        if(!reservation.fresh()) return reservation.turn();
        String answer;
        try { answer=client.complete(reservation); }
        catch(AiClient.AiFailure e) { return work.finish(reservation,null,e.code); }
        return work.finish(reservation,answer,null);
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void recoverAbandonedRequests() { work.expirePending(); }
}
