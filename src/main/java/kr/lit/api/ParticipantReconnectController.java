package kr.lit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/participation")
class ParticipantReconnectController {
    private final ParticipantReconnectService reconnects;
    ParticipantReconnectController(ParticipantReconnectService reconnects){this.reconnects=reconnects;}
    record ReconnectRequest(@NotBlank @Size(max=36) String evaluationId,
                            String participantToken) {}
    @PostMapping("/reconnect")
    ParticipantReconnectService.ReconnectResponse reconnectParticipant(@Valid @RequestBody ReconnectRequest request) {
        return reconnects.reconnectParticipant(request.evaluationId(),request.participantToken());
    }
}
