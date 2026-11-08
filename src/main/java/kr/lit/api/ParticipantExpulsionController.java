package kr.lit.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teacher/evaluations/{id}/participants")
class ParticipantExpulsionController {
    private final ParticipantExpulsionService expulsions;
    ParticipantExpulsionController(ParticipantExpulsionService expulsions){this.expulsions=expulsions;}
    @DeleteMapping("/{participantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void expelParticipant(@PathVariable String id,@PathVariable String participantId){expulsions.expelParticipant(id,participantId);}
}
