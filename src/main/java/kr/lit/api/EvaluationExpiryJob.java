package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvaluationExpiryJob {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(EvaluationExpiryJob.class);
    private final JdbcTemplate db;
    private final WorkService work;
    public EvaluationExpiryJob(JdbcTemplate db,WorkService work) { this.db=db;this.work=work; }

    @Scheduled(fixedDelayString="${lit.evaluation.expiry-check-ms:1000}",initialDelayString="${lit.evaluation.expiry-check-ms:1000}")
    public void closeExpired() {
        var ids=db.queryForList("SELECT id FROM evaluations WHERE state='STARTED' AND COALESCE(ends_at,expires_at)<=CURRENT_TIMESTAMP ORDER BY COALESCE(ends_at,expires_at),id LIMIT 100",String.class);
        for(String id:ids) {
            try {work.finishEvaluation(id,true);}
            catch(RuntimeException ex) {log.warn("Evaluation deadline processing failed: id={}, type={}",id,ex.getClass().getSimpleName());}
        }
    }
}
