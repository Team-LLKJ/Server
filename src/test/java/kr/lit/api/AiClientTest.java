package kr.lit.api;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static kr.lit.api.WorkModels.*;

class AiClientTest {
    private HttpServer server;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicReference<JsonNode> received=new AtomicReference<>();
    private final AtomicReference<String> authorization=new AtomicReference<>();
    private AiClient client(int status,String response,int delayMillis) throws Exception {
        return client("openai",status,response,delayMillis);
    }
    private AiClient client(String provider,int status,String response,int delayMillis) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses",exchange->{
            try {
                received.set(json.readTree(exchange.getRequestBody()));
                authorization.set(exchange.getRequestHeaders().getFirst(provider.equals("gemini")?"x-goog-api-key":"Authorization"));
                if(delayMillis>0) Thread.sleep(delayMillis);
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        server.start();
        return new AiClient(json,provider,"test-only-key","test-model","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/responses",1);
    }
    private Reservation reservation() {
        var now=Instant.now();
        var turn=new Turn(1,"request","q","힌트를 알려줘",null,"PENDING",null,"openai","test-model",AiPolicy.HINT_ONLY,1,now,null);
        var previous=new Turn(0,"prior","q","이전 질문","이전 응답","COMPLETED",null,"openai","test-model",AiPolicy.HINT_ONLY,0,now,now);
        return new Reservation(new Context("e","p","STARTED",now.plusSeconds(60)),turn,new Question("q",1,"문항","조건을 설명하세요"),new Answer("q","학생 초안",1,now),List.of(previous),true);
    }
    @AfterEach void stop() {if(server!=null)server.stop(0);}
    @Test void responseAndContextMatchOfficialResponsesFormat() throws Exception {
        var c=client(200,"{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[]},{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"힌트입니다\"}]}]}",0);
        assertThat(c.complete(reservation())).isEqualTo("힌트입니다");
        assertThat(received.get().get("store").asBoolean()).isFalse();
        assertThat(received.get().get("instructions").asText()).contains("최종 정답이나 완성된 답안을 제공하지 마세요");
        assertThat(received.get().get("input").toString()).contains("학생 초안","이전 질문","이전 응답","힌트를 알려줘");
        assertThat(authorization.get()).isEqualTo("Bearer test-only-key");
    }
    @Test void providerRateLimitIsSafeError() throws Exception {
        var c=client(429,"{\"error\":{\"message\":\"private upstream message\"}}",0);
        assertThatThrownBy(()->c.complete(reservation())).isInstanceOf(AiClient.AiFailure.class).hasMessage("AI_PROVIDER_RATE_LIMITED");
    }
    @Test void incompleteOutputIsNotStoredAsCompletedAnswer() throws Exception {
        var c=client(200,"{\"status\":\"incomplete\",\"output\":[]}",0);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_INCOMPLETE_RESPONSE");
    }
    @Test void requestTimeoutIsDistinguished() throws Exception {
        var c=client(200,"{}",1400);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_TIMEOUT");
    }
    @Test void noCredentialsMeansNoOutgoingRequest() {
        var c=new AiClient(json,"openai","","","http://127.0.0.1:1",1);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_NOT_CONFIGURED");
    }
    @Test void geminiIncludesContextHistoryAndSystemPolicy() throws Exception {
        var c=client("gemini",200,"{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"private thought\",\"thought\":true},{\"text\":\"힌트입니다\"}]}}]}",0);
        assertThat(c.complete(reservation())).isEqualTo("힌트입니다");
        assertThat(authorization.get()).isEqualTo("test-only-key");
        assertThat(received.get().path("systemInstruction").toString()).contains("최종 정답이나 완성된 답안을 제공하지 마세요");
        assertThat(received.get().path("contents").toString()).contains("학생 초안","이전 질문","이전 응답","힌트를 알려줘");
        assertThat(received.get().path("contents").path(2).path("role").asText()).isEqualTo("model");
        assertThat(received.get().has("input")).isFalse();
    }
    @Test void geminiTruncatedResponseIsNotSuccessful() throws Exception {
        var c=client("gemini",200,"{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}",0);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_INCOMPLETE_RESPONSE");
    }
    @Test void geminiQuotaAndSafetyBlockAreSafeFailures() throws Exception {
        var c=client("gemini",429,"{\"error\":{\"message\":\"private detail\"}}",0);
        var limited=c;
        assertThatThrownBy(()->limited.complete(reservation())).hasMessage("AI_PROVIDER_RATE_LIMITED");
        server.stop(0);
        c=client("gemini",200,"{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",0);
        var blocked=c;
        assertThatThrownBy(()->blocked.complete(reservation())).hasMessage("AI_INCOMPLETE_RESPONSE");
    }

    @Test void geminiUnavailableModelIsActionable() throws Exception {
        var c=client("gemini",404,"{\"error\":{\"status\":\"NOT_FOUND\"}}",0);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_MODEL_UNAVAILABLE");
    }
    @Test void geminiInvalidKeyIsDistinguishedWithoutExposingIt() throws Exception {
        var c=client("gemini",400,"{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}",0);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_AUTHENTICATION_FAILED");
    }
    @Test void geminiTemporaryOutageIsDistinguished() throws Exception {
        var c=client("gemini",503,"{}",0);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_PROVIDER_UNAVAILABLE");
    }

    private AiClient sequence(int[] statuses,AtomicInteger calls) throws Exception {
        client("gemini",200,"{}",0);
        server.removeContext("/v1/responses");
        server.createContext("/v1/responses",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();
                int index=calls.getAndIncrement();int status=statuses[Math.min(index,statuses.length-1)];
                String response=status==200?"{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"복구됨\"}]}}]}":"{}";
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        return new AiClient(json,"gemini","test-only-key","test-model","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/responses",10);
    }
    @Test void geminiRetriesTransientOutageAndRecoversWithinSameRequest() throws Exception {
        var calls=new AtomicInteger();var c=sequence(new int[]{503,503,200},calls);
        assertThat(c.complete(reservation())).isEqualTo("복구됨");assertThat(calls.get()).isEqualTo(3);
    }
    @Test void geminiStopsAfterThreeFailures() throws Exception {
        var calls=new AtomicInteger();var c=sequence(new int[]{503},calls);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_PROVIDER_UNAVAILABLE");assertThat(calls.get()).isEqualTo(3);
    }
    @Test void geminiDoesNotRetryQuotaErrors() throws Exception {
        var calls=new AtomicInteger();var c=sequence(new int[]{429,200},calls);
        assertThatThrownBy(()->c.complete(reservation())).hasMessage("AI_PROVIDER_RATE_LIMITED");assertThat(calls.get()).isEqualTo(1);
    }

}
