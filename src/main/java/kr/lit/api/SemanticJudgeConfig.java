package kr.lit.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 채점용 LLM 판정 설정. lit.grading.judge.*
 * 학생 대화용 lit.ai.*와 섞이지 않도록 따로 둡니다. Claude Haiku가 먼저 모두 판정하고, 애매한 것만 Gemini가 다시 판정합니다.
 * enabled=false(기본)이거나 Haiku(Anthropic) 키·모델이 없으면 꺼져 있고, 1단계·키워드 결과만 씁니다.
 */
@Component
@ConfigurationProperties(prefix="lit.grading.judge")
@org.springframework.validation.annotation.Validated
public class SemanticJudgeConfig {
    /** 프롬프트·판정 규칙 버전. 바뀌면 캐시 키가 달라져 다시 판정합니다. */
    public static final String PROMPT_VERSION="SEMANTIC_JUDGE_HAIKU_FIRST_V2";

    private boolean enabled=false;
    /** 정규화 후 이 글자 수 미만인 문장은 보내지 않습니다. */
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(200)
    private int minSentenceLength=15;
    /** 문장마다 보낼 해당 문항 AI 문장 후보 최대 수. 넘으면 2글자 조각 포함률 상위만 보냅니다. */
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(50)
    private int maxCandidates=8;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120)
    private int timeoutSeconds=60;
    /** 1차 판정: Claude Haiku (Anthropic Messages API). */
    private final Provider anthropic=new Provider();
    /** 2차 판정: Gemini (애매한 것만). */
    private final Provider gemini=new Provider();

    public static class Provider {
        private String apiKey="";
        private String model="";
        private String endpoint="";
        public String getApiKey(){return apiKey;}
        public void setApiKey(String v){apiKey=v==null?"":v.strip();}
        public String getModel(){return model;}
        public void setModel(String v){model=v==null?"":v.strip();}
        public String getEndpoint(){return endpoint;}
        public void setEndpoint(String v){endpoint=v==null?"":v.strip();}
        public boolean isConfigured(){return !apiKey.isBlank()&&!model.isBlank()&&!endpoint.isBlank();}
    }

    /** Haiku 판정이 가능할 때만 켭니다. Gemini가 없으면 애매한 것은 교사 확인(needsReview)으로만 남습니다. */
    public boolean isActive(){return enabled&&anthropic.isConfigured();}
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean v){enabled=v;}
    public int getMinSentenceLength(){return minSentenceLength;}
    public void setMinSentenceLength(int v){minSentenceLength=v;}
    public int getMaxCandidates(){return maxCandidates;}
    public void setMaxCandidates(int v){maxCandidates=v;}
    public int getTimeoutSeconds(){return timeoutSeconds;}
    public void setTimeoutSeconds(int v){timeoutSeconds=v;}
    public Provider getAnthropic(){return anthropic;}
    public Provider getGemini(){return gemini;}
}
