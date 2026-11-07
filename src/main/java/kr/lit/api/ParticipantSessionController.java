package kr.lit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class ParticipantSessionController {
    private final ParticipantSessionService sessions;
    ParticipantSessionController(ParticipantSessionService sessions){this.sessions=sessions;}
    record Acceptance(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String policyVersion) {}
    @GetMapping({"/evaluations/{id}/me","/student/evaluations/{id}/me"})
    ParticipantSessionService.Session getSession(@PathVariable String id,@RequestHeader("X-Participant-Token") String token){return sessions.getSession(id,token);}
    @PostMapping({"/evaluations/{id}/policy-acceptance","/student/evaluations/{id}/policy-acceptance"})
    ParticipantSessionService.PolicyStatus acceptPolicy(@PathVariable String id,@RequestHeader("X-Participant-Token") String token,@Valid @RequestBody Acceptance input){return sessions.acceptPolicy(id,token,input.policyVersion());}
}
