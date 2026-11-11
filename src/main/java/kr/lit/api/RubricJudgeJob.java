package kr.lit.api;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static kr.lit.api.SemanticJudgeClient.JudgeFailure;

/**
 * ③ 루브릭 판정 작업. 답안 기여도·질문 용도가 끝난 학생을 제출 순서대로 가져와 항목마다 판정하고 저장합니다.
 * LLM 호출은 DB 트랜잭션 밖에서 하며, 같은 입력(캐시 키)은 다시 부르지 않습니다. 호출은 rubric 키의 분당·하루 한도 안에서 나갑니다.
 */
@Component
public class RubricJudgeJob {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RubricJudgeJob.class);
    private final GradingQueue queue;
    private final RubricJudge judge;
    private final AssessmentService assessment;
    private final JudgeUsage usage;
    public RubricJudgeJob(GradingQueue queue,RubricJudge judge,AssessmentService assessment,JudgeUsage usage){
        this.queue=queue;this.judge=judge;this.assessment=assessment;this.usage=usage;
    }

    @Scheduled(fixedDelayString="${lit.grading.job-interval-ms:3000}",initialDelayString="${lit.grading.job-interval-ms:3000}")
    public void judgePending() {
        if(!judge.isActive()) return;
        for(var target:queue.ready(3)) process(target);
    }
    /** 한 학생의 다섯 항목을 판정합니다. 실패하면 FAILED(1분 뒤 최대 3번 다시 시도)로 두고, 그동안 화면은 기존 추천 단계를 보여 줍니다. */
    public void process(GradingQueue.Target target) {
        if(!queue.start(target)) return;
        try(var ignored=usage.open(target.evaluationId(),target.participantId(),JudgeUsage.Task.RUBRIC)) {
            var source=assessment.rubricSource(target.evaluationId(),target.participantId());
            var record=judge.buildRecord(source.input(),source.sentences());
            for(var criterion:source.criteria()) {
                if(!RubricJudge.JUDGED.contains(criterion.indicator())) continue;
                String key=judge.cacheKey(record,criterion.indicator());
                var judgment=queue.cached(target.participantId(),criterion.indicator(),key)
                        .orElseGet(()->judge.judge(record,criterion.indicator(),criterion.maxScore()));
                if(!queue.save(target,judgment,key)) return; // 그사이 다시 제출됨: 다음 주기에 마지막 제출로 다시 채점
            }
            queue.complete(target);
        } catch(JudgeFailure ex) {
            log.warn("Rubric judge failed: participant={}, code={}",target.participantId(),ex.code());
            queue.fail(target,ex.code());
        } catch(Exception ex) {
            log.warn("Rubric judge failed: participant={}, type={}",target.participantId(),ex.getClass().getSimpleName());
            queue.fail(target,"RUBRIC_"+ex.getClass().getSimpleName());
        }
    }
}
