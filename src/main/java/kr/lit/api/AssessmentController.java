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

    /** 반 전체 채점 진행(학생별 gradingStatus: QUEUED·GRADING·DONE·FAILED, 예: 채점 완료 24/30). 평가 종료 전에도 볼 수 있습니다. */
    @GetMapping("/grading-status") GradingQueue.Progress gradingStatus(@PathVariable String id){return assessment.gradingProgress(id);}
    /** 채점 실패(FAILED) 학생을 다시 채점합니다. */
    @PostMapping("/participants/{participantId}/grading/retry")
    GradingQueue.Progress retryGrading(@PathVariable String id,@PathVariable String participantId){return assessment.retryGrading(id,participantId);}
    /** 판정 호출의 실제 토큰 수(학생·용도·모델별 합계). */
    @GetMapping("/judge-usage") JudgeUsage.Summary judgeUsage(@PathVariable String id){return assessment.judgeUsage(id);}
    /** ③ 교사의 루브릭 단계 확정 {"level":4}(1~5단계). needsReview가 풀리고 점수를 다시 계산한 rubric-scores를 돌려줍니다. */
    @PatchMapping("/participants/{participantId}/criteria/{criterionId}/level")
    Scores saveLevel(@PathVariable String id,@PathVariable String participantId,@PathVariable int criterionId,@Valid @RequestBody LevelInput input) {
        return assessment.saveLevel(id,participantId,criterionId,input);
    }

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
    /** 3단계: 교사의 문장 출처 확정({"source":"AI"|"DIRECT"}, 예전 이름 label도 받음). 비율에 바로 반영된 분석을 돌려줍니다. */
    @PatchMapping({"/participants/{participantId}/sentences/{sentenceId}","/participants/{participantId}/sentences/{sentenceId}/source"})
    Analysis labelSentence(@PathVariable String id,@PathVariable String participantId,@PathVariable String sentenceId,@Valid @RequestBody SentenceLabelInput input) {
        return assessment.labelSentence(id,participantId,sentenceId,input);
    }
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
