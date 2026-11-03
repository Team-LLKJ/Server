package kr.lit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class EvaluationContentController {
    private final EvaluationContentService content;
    EvaluationContentController(EvaluationContentService content) { this.content=content; }

    record Update(@NotNull @Size(max=10000) String evaluationContent) {}

    @PutMapping("/teacher/evaluations/{id}/content")
    EvaluationContentService.Content update(@PathVariable String id,@Valid @RequestBody Update input) {
        return content.update(id,input.evaluationContent());
    }

    @GetMapping("/teacher/evaluations/{id}/content")
    EvaluationContentService.Content teacher(@PathVariable String id) { return content.teacher(id); }

    @GetMapping("/evaluations/{id}/content")
    EvaluationContentService.Content student(@PathVariable String id,
            @RequestHeader(value="X-Participant-Token",required=false) String token) {
        return content.student(id,token);
    }
}
