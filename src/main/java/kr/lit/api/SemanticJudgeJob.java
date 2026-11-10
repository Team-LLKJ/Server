package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 답안 출처 판정 2단계를 제출 후 백그라운드에서 처리합니다(semantic_status=PENDING인 분석).
 * LLM 호출은 DB 트랜잭션 밖에서 하고, 끝나면 저장된 판정으로 분석을 다시 계산합니다. 실패해도 채점 화면은 1단계 결과로 계속 보입니다.
 */
@Component
public class SemanticJudgeJob {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SemanticJudgeJob.class);
    private final JdbcTemplate db;
    private final AssessmentService assessment;
    private final SemanticJudge judge;
    public SemanticJudgeJob(JdbcTemplate db,AssessmentService assessment,SemanticJudge judge){this.db=db;this.assessment=assessment;this.judge=judge;}

    private record Target(String evaluationId,String participantId) {}

    @Scheduled(fixedDelayString="${lit.grading.job-interval-ms:3000}",initialDelayString="${lit.grading.job-interval-ms:3000}")
    public void judgePending() {
        if(!judge.isActive()) return;
        var targets=db.query("SELECT evaluation_id,participant_id FROM grading_analyses WHERE analysis_status='DONE' AND semantic_status='PENDING' ORDER BY analyzed_at LIMIT 10",
                (r,n)->new Target(r.getString("evaluation_id"),r.getString("participant_id")));
        for(var target:targets) process(target.evaluationId(),target.participantId());
    }

    /** 한 학생의 판정: 후보 고르기(트랜잭션) → LLM 호출(트랜잭션 밖) → 다시 계산해 저장(트랜잭션). */
    public void process(String evaluationId,String participantId) {
        try {
            var plan=assessment.prepareSemantic(evaluationId,participantId);
            if(plan==null) return;
            String failure=judge.run(plan);
            assessment.storeSemantic(evaluationId,participantId,failure);
        } catch(RuntimeException ex) {
            log.warn("Semantic judge failed: participant={}, type={}",participantId,ex.getClass().getSimpleName());
            try { assessment.markSemanticUnavailable(participantId); } catch(RuntimeException ignored) {}
        }
    }
}
