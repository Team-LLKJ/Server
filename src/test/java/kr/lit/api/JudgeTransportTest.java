package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static kr.lit.api.SemanticJudgeClient.*;
import static org.assertj.core.api.Assertions.*;

/** 공통 호출: 키별 분당·하루 한도, 429 재시도(Retry-After → 5·10·20초), 용도별 Gemini 키, 형식 오류 1번 재요청. */
class JudgeTransportTest {
    /** 실제로 잠들지 않고 기다린 시간만 기록하는 시계. */
    static class FakeClock implements JudgeRateLimiter.Clock {
        Instant now=Instant.parse("2026-11-11T00:00:00Z");final List<Duration> sleeps=new ArrayList<>();
        public Instant now(){return now;}
        public void sleep(Duration d){sleeps.add(d);now=now.plus(d);}
    }
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;
    final List<String> keys=new CopyOnWriteArrayList<>(),paths=new CopyOnWriteArrayList<>();
    @AfterEach void stop(){if(server!=null)server.stop(0);}

    /** 순서대로 응답(상태 코드, Retry-After, 본문)을 돌려주는 서버. */
    String serve(List<Object[]> responses) throws Exception {
        var queue=new ArrayDeque<>(responses);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();
                keys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));paths.add(exchange.getRequestURI().getPath());
                var r=queue.size()>1?queue.poll():queue.peek();
                if(r[1]!=null) exchange.getResponseHeaders().set("Retry-After",(String)r[1]);
                byte[] bytes=((String)r[2]).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders((int)r[0],bytes.length);exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        return "http://127.0.0.1:"+server.getAddress().getPort()+"/";
    }
    static final String OK="{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":3},\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}";
    SemanticJudgeConfig config(String url) {
        var c=new SemanticJudgeConfig();c.setEnabled(true);c.setTimeoutSeconds(5);
        c.getGemini().setAnalysisKey("analysis-key");c.getGemini().setRubricKey("rubric-key");c.getGemini().setModel("lite");c.getGemini().setEndpoint(url+"{model}");
        return c;
    }
    SemanticJudgeClient client(SemanticJudgeConfig c,FakeClock clock){var limiter=new JudgeRateLimiter(c);limiter.clock=clock;return new SemanticJudgeClient(json,c,limiter,new JudgeUsage(null));}

    @Test void rateLimitedResponsesWaitRetryAfterThenBackoffAndUseOnlyTheirOwnKey() throws Exception {
        var url=serve(List.of(new Object[]{429,"7","{}"},new Object[]{429,null,"{}"},new Object[]{429,null,"{}"},new Object[]{200,null,OK}));
        var clock=new FakeClock();var c=client(config(url),clock);
        assertThat(c.callGemini(GeminiKey.RUBRIC,"s","u",null,null)).isEqualTo("{}");
        assertThat(clock.sleeps).containsExactly(Duration.ofSeconds(7),Duration.ofSeconds(10),Duration.ofSeconds(20));
        assertThat(keys).containsOnly("rubric-key"); // 429가 나도 analysis 키를 빌려 쓰지 않습니다.
        keys.clear();
        assertThat(c.callGemini("s","u",null,null)).isEqualTo("{}");
        assertThat(keys).containsExactly("analysis-key");assertThat(paths).last().isEqualTo("/lite");
    }
    @Test void givesUpAfterThreeRetries() throws Exception {
        var url=serve(List.<Object[]>of(new Object[]{429,null,"{}"}));
        var clock=new FakeClock();var c=client(config(url),clock);
        assertThatThrownBy(()->c.callGemini(GeminiKey.ANALYSIS,"s","u",null,null)).isInstanceOf(JudgeFailure.class).hasMessage("JUDGE_GEMINI_RATE_LIMITED");
        assertThat(clock.sleeps).containsExactly(Duration.ofSeconds(5),Duration.ofSeconds(10),Duration.ofSeconds(20));
        assertThat(keys).hasSize(4);
    }
    @Test void limiterWaitsForTheMinuteWindowAndStopsAtTheDailyLimitPerKey() {
        var c=new SemanticJudgeConfig();c.getLimits().getRubric().setRpm(2);c.getLimits().getRubric().setRpd(3);c.getLimits().getAnalysis().setRpm(0);c.getLimits().getAnalysis().setRpd(0);
        var clock=new FakeClock();var limiter=new JudgeRateLimiter(c);limiter.clock=clock;
        limiter.acquire(JudgeRateLimiter.Bucket.RUBRIC);limiter.acquire(JudgeRateLimiter.Bucket.RUBRIC);
        assertThat(clock.sleeps).isEmpty();
        limiter.acquire(JudgeRateLimiter.Bucket.RUBRIC); // 분당 2회가 차서 1분 가까이 기다립니다.
        assertThat(clock.sleeps).hasSize(1);assertThat(clock.sleeps.get(0)).isBetween(Duration.ofSeconds(59),Duration.ofSeconds(61));
        for(int i=0;i<50;i++) limiter.acquire(JudgeRateLimiter.Bucket.ANALYSIS); // 다른 키는 영향 없음(제한 없음)
        assertThatThrownBy(()->limiter.acquire(JudgeRateLimiter.Bucket.RUBRIC)).isInstanceOf(JudgeFailure.class).hasMessage("JUDGE_RUBRIC_DAILY_LIMIT");
        clock.now=clock.now.plus(Duration.ofHours(25));
        limiter.acquire(JudgeRateLimiter.Bucket.RUBRIC);
        assertThat(limiter.snapshot()).containsKey("RUBRIC");
    }
    @Test void queueTimeoutFailsInsteadOfWaitingForever() {
        var c=new SemanticJudgeConfig();c.getLimits().getAnthropic().setRpm(1);c.getLimits().setMaxWaitSeconds(10);
        var clock=new FakeClock();var limiter=new JudgeRateLimiter(c);limiter.clock=clock;
        limiter.acquire(JudgeRateLimiter.Bucket.ANTHROPIC);
        assertThatThrownBy(()->limiter.acquire(JudgeRateLimiter.Bucket.ANTHROPIC)).hasMessage("JUDGE_ANTHROPIC_QUEUE_TIMEOUT");
    }
    @Test void formatErrorsAreRequestedOnceMore() {
        var calls=new int[1];
        assertThat(SemanticJudgeClient.retryFormat(()->{if(calls[0]++==0)throw new JudgeFailure("X_GEMINI_INVALID_JSON");return "ok";})).isEqualTo("ok");
        assertThat(calls[0]).isEqualTo(2);
        calls[0]=0;
        assertThatThrownBy(()->SemanticJudgeClient.retryFormat(()->{calls[0]++;throw new JudgeFailure("X_GEMINI_MISSING_RESULT");})).hasMessage("X_GEMINI_MISSING_RESULT");
        assertThat(calls[0]).isEqualTo(2);
        calls[0]=0;
        assertThatThrownBy(()->SemanticJudgeClient.retryFormat(()->{calls[0]++;throw new JudgeFailure("JUDGE_GEMINI_HTTP_500");})).hasMessage("JUDGE_GEMINI_HTTP_500");
        assertThat(calls[0]).isEqualTo(1); // HTTP 오류는 형식 재요청 대상이 아님(작업 단위로 다시 시도)
    }
    @Test void analysisKeyFallsBackToRubricKey() {
        // 11/13 운영: rubric 키만 있어 질문 용도 분류(analysis 키)가 꺼져 있었습니다.
        var c=new SemanticJudgeConfig();c.setEnabled(true);c.getGemini().setModel("m");c.getGemini().setEndpoint("http://x/{model}");
        c.getGemini().setRubricKey("r");
        assertThat(c.isPurposeActive()).isTrue();assertThat(c.isRubricActive()).isTrue();
        assertThat(c.getGemini().getApiKey()).isEqualTo("r");assertThat(c.getGemini().getRubricKey()).isEqualTo("r");assertThat(c.getGemini().isRubricKeyShared()).isTrue();
    }
    @Test void rubricKeyFallsBackToAnalysisKey() {
        var c=new SemanticJudgeConfig();c.setEnabled(true);c.getGemini().setModel("m");c.getGemini().setEndpoint("http://x/{model}");
        assertThat(c.isRubricActive()).isFalse();
        var client=client(c,new FakeClock());
        assertThatThrownBy(()->client.callGemini(GeminiKey.RUBRIC,"s","u",null,null)).hasMessage("JUDGE_GEMINI_NOT_CONFIGURED");
        // 운영에 rubric 키가 없어도 analysis 키로 학생 단위 판정이 켜집니다(한도는 analysis 버킷을 같이 씀).
        c.getGemini().setAnalysisKey("a");
        assertThat(c.isPurposeActive()).isTrue();assertThat(c.isRubricActive()).isTrue();
        assertThat(c.getGemini().getRubricKey()).isEqualTo("a");assertThat(c.getGemini().isRubricKeyShared()).isTrue();
        c.getGemini().setRubricKey("r");assertThat(c.getGemini().getRubricKey()).isEqualTo("r");assertThat(c.getGemini().isRubricKeyShared()).isFalse();
        assertThat(c.getGemini().getRubricModel()).isEqualTo("m");
    }
}
