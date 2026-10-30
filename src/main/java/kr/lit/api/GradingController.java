package kr.lit.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static kr.lit.api.ReportModels.*;

@RestController
@RequestMapping("/api/teacher/evaluations/{id}/students/{participantId}")
class GradingController {
    private final GradingService grading;
    private final ReportService reports;
    GradingController(GradingService grading,ReportService reports) {this.grading=grading;this.reports=reports;}
    @GetMapping("/answers")
    GradingAnswers answers(@PathVariable String id,@PathVariable String participantId){return grading.answers(id,participantId);}
    @GetMapping("/conversations")
    Object conversations(@PathVariable String id,@PathVariable String participantId,@RequestParam(required=false) String questionId,
                         @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="200") int size){return grading.conversations(id,participantId,questionId,page,size);}
    @GetMapping("/activity")
    Activity activity(@PathVariable String id,@PathVariable String participantId){return grading.activity(id,participantId);}
    @GetMapping("/review")
    Rubric rubric(@PathVariable String id,@PathVariable String participantId){return grading.rubric(id,participantId);}
    @PutMapping("/review/criteria/{criterionId}")
    Rubric score(@PathVariable String id,@PathVariable String participantId,@PathVariable String criterionId,@Valid @RequestBody ScoreInput input){return grading.saveCriterion(id,participantId,criterionId,input);}
    @GetMapping("/review/history")
    Object history(@PathVariable String id,@PathVariable String participantId,@RequestParam(defaultValue="0") long afterId,
                   @RequestParam(defaultValue="100") int limit){return reports.reviewHistory(id,participantId,afterId,limit);}
}
