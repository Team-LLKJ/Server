package kr.lit.api;

import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "lit.grading.immediate-submission-analysis", matchIfMissing = true)
public class SubmissionAnalysisDispatcher {
    private static final Logger log = LoggerFactory.getLogger(SubmissionAnalysisDispatcher.class);
    private final TaskExecutor executor;
    private final GradingAnalysisJob analysis;
    private final ProcessGradingWorker processGrading;

    public SubmissionAnalysisDispatcher(@Qualifier("applicationTaskExecutor") TaskExecutor executor,
                                        GradingAnalysisJob analysis, ProcessGradingWorker processGrading) {
        this.executor = executor;
        this.analysis = analysis;
        this.processGrading = processGrading;
    }

    record Request(String evaluationId, String participantId, String jobId) {}

    @TransactionalEventListener
    public void startAnalysis(Request request) {
        executeAnalysis(request.jobId(), () -> analysis.analyzeSubmission(request.evaluationId(), request.participantId()));
        executeAnalysis(request.jobId(), () -> processGrading.processJob(request.jobId()));
    }

    private void executeAnalysis(String jobId, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ex) {
            // 작업은 제출과 함께 저장되므로 기존 스케줄러가 다시 확인합니다.
            log.warn("Submission analysis dispatch deferred: job={}", jobId);
        }
    }
}
