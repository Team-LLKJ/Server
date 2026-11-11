package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;

/** 제출 직후 예약된(PENDING) 분석을 처리합니다. 학생 한 명씩 별도 트랜잭션입니다. */
@Component
public class GradingAnalysisJob {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GradingAnalysisJob.class);
    private final JdbcTemplate db;
    private final AssessmentService assessment;
    public GradingAnalysisJob(JdbcTemplate db,AssessmentService assessment) { this.db=db;this.assessment=assessment; }

    private record Pending(String evaluationId,String participantId,OffsetDateTime requestVersion) {}

    @Scheduled(fixedDelayString="${lit.grading.job-interval-ms:3000}",initialDelayString="${lit.grading.job-interval-ms:3000}")
    public void analyzePending() {
        var pending=db.query("SELECT evaluation_id,participant_id,requested_at FROM grading_analyses WHERE analysis_status='PENDING' ORDER BY requested_at LIMIT 50",
                (r,n)->new Pending(r.getString("evaluation_id"),r.getString("participant_id"),r.getObject("requested_at",OffsetDateTime.class)));
        pending.forEach(this::analyzeTarget);
    }

    void analyzeSubmission(String evaluationId,String participantId) {
        db.query("SELECT requested_at FROM grading_analyses WHERE evaluation_id=? AND participant_id=? AND analysis_status='PENDING'",
                (r,n)->new Pending(evaluationId,participantId,r.getObject("requested_at",OffsetDateTime.class)),evaluationId,participantId)
                .forEach(this::analyzeTarget);
    }

    private void analyzeTarget(Pending target) {
        try { assessment.analyzeForJob(target.evaluationId(),target.participantId(),target.requestVersion()); }
        catch(RuntimeException ex) {
            log.warn("Grading analysis failed: participant={}, type={}",target.participantId(),ex.getClass().getSimpleName());
            try { assessment.markFailed(target.participantId(),target.requestVersion(),ex.getClass().getSimpleName()); } catch(RuntimeException ignored) {}
        }
    }
}
