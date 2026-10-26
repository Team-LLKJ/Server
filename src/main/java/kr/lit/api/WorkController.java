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

    @PutMapping("/teacher/evaluations/{id}/questions")
    Configuration configure(@PathVariable String id,@Valid @RequestBody Configure input) { return work.configure(id,input); }
    @GetMapping("/teacher/evaluations/{id}/questions")
    Configuration configuration(@PathVariable String id) { return work.configuration(id); }
    @GetMapping("/teacher/evaluations/{id}/participants/{participantId}/learning-events")
    Object teacherEvents(@PathVariable String id,@PathVariable String participantId,
                         @RequestParam(defaultValue="0") @Min(0) long afterId,
                         @RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        return work.teacherEvents(id,participantId,afterId,limit);
    }
    @GetMapping("/evaluations/{id}/questions")
    Workspace workspace(@PathVariable String id,@org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account) {
        return work.workspace(id,account.userId());
    }
    @GetMapping("/evaluations/{id}/questions/{questionId}")
    QuestionAnswer select(@PathVariable String id,@PathVariable String questionId,@org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account) {
        return work.select(id,account.userId(),questionId);
    }
    @PutMapping("/evaluations/{id}/questions/{questionId}/answer")
    Answer save(@PathVariable String id,@PathVariable String questionId,@org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account,
                @Valid @RequestBody SaveAnswer input) { return work.save(id,account.userId(),questionId,input); }
    @PostMapping("/evaluations/{id}/questions/{questionId}/ai/questions")
    ResponseEntity<Turn> ask(@PathVariable String id,@PathVariable String questionId,
                            @org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account,@Valid @RequestBody Ask input) {
        var result=ai.ask(id,account.userId(),questionId,input);
        int status=result.state().equals("PENDING")?202:result.state().equals("COMPLETED")?200:
                "AI_NOT_CONFIGURED".equals(result.errorCode())?503:"AI_TIMEOUT".equals(result.errorCode())?504:502;
        return ResponseEntity.status(status).body(result);
    }
    @GetMapping("/evaluations/{id}/conversations")
    Object conversations(@PathVariable String id,@org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account,
                         @RequestParam(required=false) String questionId,@RequestParam(defaultValue="0") @Min(0) long afterId,
                         @RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        return work.conversations(id,account.userId(),questionId,afterId,limit);
    }
    @GetMapping("/evaluations/{id}/learning-events")
    Object events(@PathVariable String id,@org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account,
                  @RequestParam(defaultValue="0") @Min(0) long afterId,@RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        return work.learningEvents(id,account.userId(),afterId,limit);
    }
    @GetMapping("/evaluations/{id}/submission-preview")
    Review preview(@PathVariable String id,@org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account) { return work.review(id,account.userId()); }
    @PostMapping("/evaluations/{id}/submission")
    Submission submit(@PathVariable String id,@org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account,@Valid @RequestBody Confirm input) {
        return work.submit(id,account.userId(),input.reviewToken());
    }
    @GetMapping("/evaluations/{id}/submission")
    Submission submission(@PathVariable String id,@org.springframework.security.core.annotation.AuthenticationPrincipal kr.lit.auth.AuthModels.Principal account) { return work.submission(id,account.userId()); }
}
