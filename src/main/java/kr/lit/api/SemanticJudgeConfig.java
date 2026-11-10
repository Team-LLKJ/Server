package kr.lit.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 답안 출처 판정 2단계(LLM으로 바꿔 쓴 문장 판정) 설정. lit.grading.judge.*
 * 학생 대화용 lit.ai.*와 섞이지 않도록 따로 둡니다. enabled=false(기본)면 1단계 결과만 씁니다.
 */
@Component
@ConfigurationProperties(prefix="lit.grading.judge")
@org.springframework.validation.annotation.Validated
public class SemanticJudgeConfig {
    /** 프롬프트·판정 규칙 버전. 바뀌면 캐시 키가 달라져 다시 판정합니다. */
    public static final String PROMPT_VERSION="SEMANTIC_JUDGE_LEVEL_V1";

    private boolean enabled=false;
    /** 정규화 후 이 글자 수 미만인 문장은 보내지 않습니다. */
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(200)
    private int minSentenceLength=15;
    /** 문장마다 보낼 해당 문항 AI 문장 후보 최대 수. 넘으면 2글자 조각 포함률 상위만 보냅니다. */
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(50)
    private int maxCandidates=8;
    /** Gemini 단계 1 문장 중 가까운 AI 문장과의 유사도 상위 몇 %를 Sonnet에 보낼지. */
    @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0")
    private double level1ReviewShare=0.2;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120)
    private int timeoutSeconds=60;
    private final Provider gemini=new Provider();
    private final Provider anthropic=new Provider();

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

    /** Gemini 판정이 가능할 때만 2단계를 켭니다. Sonnet이 없으면 Gemini 단계 3 문장은 교사 확인으로만 남습니다. */
    public boolean isActive(){return enabled&&gemini.isConfigured();}
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean v){enabled=v;}
    public int getMinSentenceLength(){return minSentenceLength;}
    public void setMinSentenceLength(int v){minSentenceLength=v;}
    public int getMaxCandidates(){return maxCandidates;}
    public void setMaxCandidates(int v){maxCandidates=v;}
    public double getLevel1ReviewShare(){return level1ReviewShare;}
    public void setLevel1ReviewShare(double v){level1ReviewShare=v;}
    public int getTimeoutSeconds(){return timeoutSeconds;}
    public void setTimeoutSeconds(int v){timeoutSeconds=v;}
    public Provider getGemini(){return gemini;}
    public Provider getAnthropic(){return anthropic;}
}
