package kr.lit.api;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 시작할 때 AI 판정 단계별 켜짐 여부를 남깁니다(키 값은 남기지 않음). 운영에서 질문 용도 분류가 꺼져 있던 일을 바로 알아보기 위한 것입니다. */
@Component
class JudgeStartupLog {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(JudgeStartupLog.class);
    private final SemanticJudgeConfig config;
    JudgeStartupLog(SemanticJudgeConfig config){this.config=config;}
    @EventListener(ApplicationReadyEvent.class)
    void report() {
        var g=config.getGemini();
        log.info("AI judge: enabled={}, semantic(Haiku)={}, purpose(Gemini)={}, rubric(Gemini)={}, geminiKeys={}",
                config.isEnabled(),config.isActive(),config.isPurposeActive(),config.isRubricActive(),
                g.getApiKey().isBlank()?"none":g.isRubricKeyShared()?"shared":"analysis+rubric");
    }
}
