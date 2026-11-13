package kr.lit.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 채점용 LLM 판정 설정. lit.grading.judge.* — 학생 대화용 lit.ai.*와 섞지 않습니다.
 * <ul>
 *   <li>① 답안 기여도: Claude Haiku 먼저 → Haiku 단계 2만 Gemini(analysis 키).</li>
 *   <li>② 질문 용도: Gemini(analysis 키) 3번 → 엇갈린 질문만 Haiku.</li>
 *   <li>③ 루브릭 단계: Gemini(rubric 키) 3번 → 엇갈린 항목만 Haiku.</li>
 * </ul>
 * Gemini 키는 용도마다 다른 Google 프로젝트에서 받습니다(한도는 프로젝트 단위). 키마다 분당·하루 호출 수를 따로 세고,
 * 한 키가 429로 막혀도 다른 용도의 키를 빌려 쓰지 않습니다. enabled=false(기본)이면 모두 꺼지고 기존 규칙 결과만 씁니다.
 */
@Component
@ConfigurationProperties(prefix="lit.grading.judge")
@org.springframework.validation.annotation.Validated
public class SemanticJudgeConfig {
    /** 프롬프트·판정 규칙 버전. 바뀌면 캐시 키가 달라져 다시 판정합니다. */
    public static final String PROMPT_VERSION="SEMANTIC_JUDGE_HAIKU_FIRST_V3";

    private boolean enabled=false;
    /** 정규화 후 이 글자 수 미만인 문장은 보내지 않습니다. */
    @Min(1) @Max(200) private int minSentenceLength=15;
    /** 문장마다 보낼 해당 문항 AI 문장 후보 최대 수. 넘으면 2글자 조각 포함률 상위만 보냅니다. */
    @Min(1) @Max(50) private int maxCandidates=8;
    @Min(1) @Max(120) private int timeoutSeconds=60;
    /** 판정용 Claude Haiku (Anthropic Messages API). 세 판정이 함께 씁니다. */
    private final Provider anthropic=new Provider();
    /** 판정용 Gemini. analysis-key(답안 기여도 재판정·질문 용도), rubric-key(루브릭)는 서로 다른 프로젝트의 키입니다. */
    private final Gemini gemini=new Gemini();
    /** 키별 호출 한도. 대기열이 이 한도 안에서 순서대로 보냅니다. */
    private final Limits limits=new Limits();
    private final Rubric rubric=new Rubric();

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
    /** apiKey는 analysis-key의 예전 이름입니다(lit.grading.judge.gemini.api-key도 그대로 받습니다). */
    public static class Gemini extends Provider {
        private String rubricKey="";
        private String rubricModel="";
        public String getAnalysisKey(){return getApiKey();}
        public void setAnalysisKey(String v){if(v!=null&&!v.isBlank())setApiKey(v);}
        /** rubric 키가 없으면 analysis 키를 같이 씁니다(학생 단위 루브릭 판정이 키 하나 빠져 꺼지지 않도록). 한도는 같은 프로젝트를 나눠 씁니다. */
        public String getRubricKey(){return rubricKey.isBlank()?getApiKey():rubricKey;}
        public boolean isRubricKeyShared(){return rubricKey.isBlank()||rubricKey.equals(getApiKey());}
        public void setRubricKey(String v){rubricKey=v==null?"":v.strip();}
        public String getRubricModel(){return rubricModel.isBlank()?getModel():rubricModel;}
        public void setRubricModel(String v){rubricModel=v==null?"":v.strip();}
        public boolean isRubricConfigured(){return !getRubricKey().isBlank()&&!getRubricModel().isBlank()&&!getEndpoint().isBlank();}
    }
    /** 분당 호출 수(rpm)와 하루 호출 수(rpd). 0이면 제한 없음. 실제 프로젝트·요금제 한도에 맞춰 설정합니다. */
    public static class Limit {
        @Min(0) private int rpm;
        @Min(0) private int rpd;
        public Limit(){}
        Limit(int rpm,int rpd){this.rpm=rpm;this.rpd=rpd;}
        public int getRpm(){return rpm;}
        public void setRpm(int v){rpm=v;}
        public int getRpd(){return rpd;}
        public void setRpd(int v){rpd=v;}
    }
    public static class Limits {
        private final Limit analysis=new Limit(15,1000);
        private final Limit rubric=new Limit(15,1000);
        private final Limit anthropic=new Limit(50,0);
        /** 한도 때문에 기다리는 최대 시간(초). 넘으면 이번 호출은 실패로 두고 작업이 나중에 다시 시도합니다. */
        @Min(1) @Max(3600) private int maxWaitSeconds=300;
        public Limit getAnalysis(){return analysis;}
        public Limit getRubric(){return rubric;}
        public Limit getAnthropic(){return anthropic;}
        public int getMaxWaitSeconds(){return maxWaitSeconds;}
        public void setMaxWaitSeconds(int v){maxWaitSeconds=v;}
    }
    /** ③ 루브릭 판정 설정. */
    public static class Rubric {
        /** 항목마다 Gemini를 부르는 횟수. */
        @Min(1) @Max(5) private int runs=3;
        /** true면 앞 두 번이 같을 때 세 번째를 부르지 않습니다(비용 절약, 아직 검증 전이라 기본 false). */
        private boolean stopWhenTwoAgree=false;
        /** AI를 썼지만 학생 질문이 이 수 이하면 모델을 부르지 않고 교사에게 넘깁니다(INSUFFICIENT_DATA). */
        @Min(0) @Max(20) private int insufficientQuestionCount=2;
        /** 학생 기록에 넣는 AI 답변 하나의 최대 글자 수(학생 질문·답안·설명란은 자르지 않습니다). */
        @Min(100) @Max(20000) private int maxAiAnswerChars=1500;
        public int getRuns(){return runs;}
        public void setRuns(int v){runs=v;}
        public boolean isStopWhenTwoAgree(){return stopWhenTwoAgree;}
        public void setStopWhenTwoAgree(boolean v){stopWhenTwoAgree=v;}
        public int getInsufficientQuestionCount(){return insufficientQuestionCount;}
        public void setInsufficientQuestionCount(int v){insufficientQuestionCount=v;}
        public int getMaxAiAnswerChars(){return maxAiAnswerChars;}
        public void setMaxAiAnswerChars(int v){maxAiAnswerChars=v;}
    }

    /** ① 답안 기여도 2단계: Haiku가 있어야 켭니다. Gemini(analysis)가 없으면 애매한 것은 교사 확인으로만 남습니다. */
    public boolean isActive(){return enabled&&anthropic.isConfigured();}
    /** ② 질문 용도: Gemini(analysis)가 있어야 켭니다. Haiku가 없으면 엇갈린 질문은 Gemini 다수결 임시 용도로 둡니다. */
    public boolean isPurposeActive(){return enabled&&gemini.isConfigured();}
    /** ③ 루브릭: Gemini(rubric)가 있어야 켭니다. 꺼져 있으면 기존 규칙 기반 추천 단계를 씁니다. */
    public boolean isRubricActive(){return enabled&&gemini.isRubricConfigured();}
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean v){enabled=v;}
    public int getMinSentenceLength(){return minSentenceLength;}
    public void setMinSentenceLength(int v){minSentenceLength=v;}
    public int getMaxCandidates(){return maxCandidates;}
    public void setMaxCandidates(int v){maxCandidates=v;}
    public int getTimeoutSeconds(){return timeoutSeconds;}
    public void setTimeoutSeconds(int v){timeoutSeconds=v;}
    public Provider getAnthropic(){return anthropic;}
    public Gemini getGemini(){return gemini;}
    public Limits getLimits(){return limits;}
    public Rubric getRubric(){return rubric;}
}
