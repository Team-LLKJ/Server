package kr.lit.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static kr.lit.api.AssessmentModels.*;

/** 교사 채점 화면(/teacher/grading) API. 모든 경로는 소유 교사의 Bearer 토큰이 필요합니다. */
@RestController
@RequestMapping("/api/teacher/evaluations/{id}")
class AssessmentController {
    private final AssessmentService assessment;
    AssessmentController(AssessmentService assessment) { this.assessment=assessment; }

    @GetMapping("/analysis/definition") Object getAnalysisDefinition(@PathVariable String id){return assessment.getAnalysisDefinition(id);}
    @GetMapping("/submissions")
    Submissions submissions(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size,
                            @RequestParam(defaultValue="") String query) { return assessment.submissions(id,page,size,query); }
    @GetMapping("/participants/{participantId}/answers")
    Answers answers(@PathVariable String id,@PathVariable String participantId) { return assessment.answers(id,participantId); }
    @GetMapping("/participants/{participantId}/conversations")
    List<Conversation> conversations(@PathVariable String id,@PathVariable String participantId,@RequestParam(required=false) String questionId,
                                     @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="200") int size) {
        return assessment.conversations(id,participantId,questionId,page,size);
    }
    @GetMapping("/participants/{participantId}/analysis")
    Analysis analysis(@PathVariable String id,@PathVariable String participantId) { return assessment.analysis(id,participantId); }
    @PostMapping("/analysis/run")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    AnalysisRun run(@PathVariable String id) { return assessment.run(id); }

    @GetMapping("/rubric")
    Rubric rubric(@PathVariable String id) { return assessment.rubric(id); }
    @PutMapping("/rubric")
    Rubric saveRubric(@PathVariable String id,@Valid @RequestBody RubricInput input) { return assessment.saveRubric(id,input); }
    @DeleteMapping("/rubric")
    Rubric resetRubric(@PathVariable String id) { return assessment.resetRubric(id); }

    @GetMapping("/participants/{participantId}/rubric-scores")
    Scores scores(@PathVariable String id,@PathVariable String participantId) { return assessment.scores(id,participantId); }
    @PutMapping("/participants/{participantId}/rubric-scores/{criterionId}")
    Scores score(@PathVariable String id,@PathVariable String participantId,@PathVariable int criterionId,@Valid @RequestBody ScoreInput input) {
        return assessment.saveScore(id,participantId,criterionId,input);
    }
    @GetMapping("/participants/{participantId}/rubric-scores/history")
    List<ScoreChange> history(@PathVariable String id,@PathVariable String participantId,@RequestParam(defaultValue="0") long afterId,
                              @RequestParam(defaultValue="100") int limit) { return assessment.history(id,participantId,afterId,limit); }
    @PostMapping("/participants/{participantId}/grade/confirm")
    Scores confirm(@PathVariable String id,@PathVariable String participantId,@RequestBody(required=false) ConfirmInput input) {
        return assessment.confirm(id,participantId,input!=null&&Boolean.TRUE.equals(input.acceptAiForMissing()));
    }
    @PutMapping("/participants/{participantId}/compliance-review")
    Scores compliance(@PathVariable String id,@PathVariable String participantId,@Valid @RequestBody ComplianceInput input) {
        return assessment.reviewCompliance(id,participantId,input);
    }
}
