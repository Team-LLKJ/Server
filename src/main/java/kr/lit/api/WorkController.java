package kr.lit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static kr.lit.api.WorkModels.*;

@RestController
@RequestMapping("/api")
@Validated
class WorkController {
    private final WorkService work;
    private final AiService ai;
    WorkController(WorkService work,AiService ai) { this.work=work;this.ai=ai; }

    @PutMapping({"/teacher/evaluations/{id}/questions","/teacher/evaluations/{id}/configuration"})
    Configuration configure(@PathVariable String id,@Valid @RequestBody Configure input) { return work.configure(id,input); }
    @GetMapping({"/teacher/evaluations/{id}/questions","/teacher/evaluations/{id}/configuration"})
    Configuration configuration(@PathVariable String id) { return work.configuration(id); }
    @GetMapping("/teacher/evaluations/{id}/participants/{participantId}/learning-events")
    Object teacherEvents(@PathVariable String id,@PathVariable String participantId,
                         @RequestParam(defaultValue="0") @Min(0) long afterId,
                         @RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        return work.teacherEvents(id,participantId,afterId,limit);
    }
    @GetMapping({"/evaluations/{id}/questions","/student/evaluations/{id}/workspace"})
    Workspace workspace(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token) {
        return work.workspace(id,token);
    }
    @PostMapping({"/student/evaluations/{id}/events","/evaluations/{id}/events","/evaluations/{id}/learning-events","/student/evaluations/{id}/learning-events"})
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Event recordEvent(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token,
                      @Valid @RequestBody ClientEvent input) { return work.recordClientEvent(id,token,input); }
    @GetMapping({"/evaluations/{id}/ai-disclosure","/student/evaluations/{id}/ai-disclosure"})
    DisclosureView disclosure(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token) {
        return work.disclosure(id,token);
    }
    @PutMapping({"/evaluations/{id}/ai-disclosure","/student/evaluations/{id}/ai-disclosure"})
    DisclosureView saveDisclosure(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token,
                                  @Valid @RequestBody Disclosure input) { return work.saveDisclosure(id,token,input); }
    @GetMapping({"/evaluations/{id}/questions/{questionId}","/student/evaluations/{id}/questions/{questionId}"})
    QuestionAnswer select(@PathVariable String id,@PathVariable String questionId,@RequestHeader(value="X-Participant-Token",required=false) String token) {
        return work.select(id,token,questionId);
    }
    @PutMapping({"/evaluations/{id}/questions/{questionId}/answer","/student/evaluations/{id}/questions/{questionId}/answer"})
    Answer save(@PathVariable String id,@PathVariable String questionId,@RequestHeader(value="X-Participant-Token",required=false) String token,
                @Valid @RequestBody SaveAnswer input) { return work.save(id,token,questionId,input); }
    @PostMapping({"/evaluations/{id}/questions/{questionId}/ai/questions","/student/evaluations/{id}/questions/{questionId}/ai/questions"})
    ResponseEntity<Turn> ask(@PathVariable String id,@PathVariable String questionId,
                            @RequestHeader(value="X-Participant-Token",required=false) String token,@Valid @RequestBody Ask input) {
        var result=ai.ask(id,token,questionId,input);
        int status=result.state().equals("PENDING")?202:result.state().equals("COMPLETED")?200:
                "AI_NOT_CONFIGURED".equals(result.errorCode())?503:"AI_TIMEOUT".equals(result.errorCode())?504:502;
        return ResponseEntity.status(status).body(result);
    }
    @GetMapping({"/evaluations/{id}/conversations","/student/evaluations/{id}/conversations"})
    Object conversations(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token,
                         @RequestParam(required=false) String questionId,@RequestParam(defaultValue="0") @Min(0) long afterId,
                         @RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        return work.conversations(id,token,questionId,afterId,limit);
    }
    @GetMapping({"/evaluations/{id}/learning-events","/student/evaluations/{id}/learning-events"})
    Object events(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token,
                  @RequestParam(defaultValue="0") @Min(0) long afterId,@RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        return work.learningEvents(id,token,afterId,limit);
    }
    @GetMapping({"/evaluations/{id}/submission-preview","/student/evaluations/{id}/submission-preview"})
    Review preview(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token) { return work.review(id,token); }
    @PostMapping({"/evaluations/{id}/submission","/student/evaluations/{id}/submission"})
    Submission submit(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token,@Valid @RequestBody Confirm input) {
        return work.submit(id,token,input.reviewToken());
    }
    @GetMapping({"/evaluations/{id}/submission","/student/evaluations/{id}/submission"})
    Submission submission(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token) { return work.submission(id,token); }
}
