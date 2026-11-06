package kr.lit.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;

@RestController
@RequestMapping("/api/teacher/evaluations/{id}/process-grading")
class ProcessGradingController {
    private final ProcessGradingService service;
    ProcessGradingController(ProcessGradingService service){this.service=service;}
    @GetMapping("/rubric") Map<String,Object> getRubric(@PathVariable String id){return service.getRubric(id);}
    @GetMapping("/policy") Policy getPolicy(@PathVariable String id){return service.getPolicy(id);}
    @PutMapping("/policy") Policy savePolicy(@PathVariable String id,@Valid @RequestBody PolicyRequest input){return service.savePolicy(id,input);}
    @GetMapping("/participants/{pid}/runs") List<Job> getRuns(@PathVariable String id,@PathVariable String pid){return service.getRuns(id,pid);}
    @PostMapping("/participants/{pid}/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    Job queueAnalysis(@PathVariable String id,@PathVariable String pid){return service.queueAnalysis(id,pid);}
    @GetMapping("/participants/{pid}/runs/{jobId}") Job getRun(@PathVariable String id,@PathVariable String pid,@PathVariable String jobId){return service.getRun(id,pid,jobId);}
    @GetMapping("/participants/{pid}/runs/{jobId}/evidence") Input getEvidence(@PathVariable String id,@PathVariable String pid,@PathVariable String jobId){return service.getEvidence(id,pid,jobId);}
    @GetMapping("/participants/{pid}/runs/{jobId}/review") Review getReview(@PathVariable String id,@PathVariable String pid,@PathVariable String jobId){return service.getReview(id,pid,jobId);}
    @PutMapping("/participants/{pid}/runs/{jobId}/review") Review saveReview(@PathVariable String id,@PathVariable String pid,@PathVariable String jobId,@Valid @RequestBody ReviewRequest input){return service.saveReview(id,pid,jobId,input);}
    @GetMapping("/participants/{pid}/runs/{jobId}/review/history") List<Review> getReviewHistory(@PathVariable String id,@PathVariable String pid,@PathVariable String jobId){return service.getReviewHistory(id,pid,jobId);}
    @PostMapping("/participants/{pid}/runs/{jobId}/confirm") Review confirmReview(@PathVariable String id,@PathVariable String pid,@PathVariable String jobId,@Valid @RequestBody ConfirmRequest input){return service.confirmReview(id,pid,jobId,input);}
}
