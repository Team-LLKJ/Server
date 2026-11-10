package kr.lit.api;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 학생 질문 용도를 백그라운드에서 분류합니다(PurposeClassifier). 채점 화면은 기다리지 않고, 분류 전에는 키워드 분류로 대체됩니다.
 * 분류가 저장되면 이미 계산된 AI 활용 분석(grading_analyses)을 같은 분류로 다시 계산합니다.
 */
@Component
public class PurposeClassificationJob {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PurposeClassificationJob.class);
    private final PurposeClassifier classifier;
    private final AssessmentService assessment;
    public PurposeClassificationJob(PurposeClassifier classifier,AssessmentService assessment){this.classifier=classifier;this.assessment=assessment;}

    @Scheduled(fixedDelayString="${lit.grading.job-interval-ms:3000}",initialDelayString="${lit.grading.job-interval-ms:3000}")
    public void classifyPending() {
        if(!classifier.isActive()) return;
        for(String participantId:classifier.participantsToClassify(5)) process(participantId);
    }
    /** LLM 호출은 트랜잭션 밖에서 하고, 저장 뒤 분석을 다시 계산합니다. */
    public void process(String participantId) {
        try {
            if(classifier.classifyParticipant(participantId)>0) assessment.refreshAfterPurpose(participantId);
        } catch(RuntimeException ex) {
            log.warn("Purpose classification job failed: participant={}, type={}",participantId,ex.getClass().getSimpleName());
        }
    }
}
