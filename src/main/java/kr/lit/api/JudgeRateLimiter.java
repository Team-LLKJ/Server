package kr.lit.api;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.SemanticJudgeClient.JudgeFailure;

/**
 * 판정용 키마다 분당·하루 호출 수를 따로 세는 대기열입니다. 호출 전에 acquire()로 자리를 받고, 분당 한도가 차면
 * 가장 오래된 호출이 1분을 넘길 때까지 기다립니다(최대 maxWaitSeconds). 하루 한도가 차면 바로 실패로 돌려 작업이 나중에 다시 시도하게 합니다.
 * 한 키의 한도는 다른 키에 영향을 주지 않습니다. 서버 한 대(EC2 1대) 기준의 메모리 계수기입니다.
 */
@Component
public class JudgeRateLimiter {
    public enum Bucket { ANALYSIS, RUBRIC, ANTHROPIC }
    /** 테스트에서 시간을 바꿀 수 있게 둡니다. */
    interface Clock { Instant now(); void sleep(Duration d) throws InterruptedException; }
    static final Clock SYSTEM=new Clock() {
        public Instant now(){return Instant.now();}
        public void sleep(Duration d) throws InterruptedException {Thread.sleep(Math.max(1,d.toMillis()));}
    };

    private final SemanticJudgeConfig config;
    private final Map<Bucket,Deque<Instant>> minute=new EnumMap<>(Bucket.class),day=new EnumMap<>(Bucket.class);
    Clock clock=SYSTEM;
    public JudgeRateLimiter(SemanticJudgeConfig config){
        this.config=config;
        for(var b:Bucket.values()){minute.put(b,new ArrayDeque<>());day.put(b,new ArrayDeque<>());}
    }

    private SemanticJudgeConfig.Limit limit(Bucket b){
        var l=config.getLimits();
        return switch(b){case ANALYSIS->l.getAnalysis();case RUBRIC->l.getRubric();case ANTHROPIC->l.getAnthropic();};
    }
    /** 자리가 날 때까지 기다렸다가 한 번의 호출을 기록합니다. */
    public void acquire(Bucket bucket) {
        var deadline=clock.now().plusSeconds(config.getLimits().getMaxWaitSeconds());
        while(true) {
            Duration wait;
            synchronized(this) {
                var now=clock.now();var limit=limit(bucket);
                var m=minute.get(bucket);var d=day.get(bucket);
                while(!m.isEmpty()&&!m.peekFirst().isAfter(now.minusSeconds(60))) m.pollFirst();
                while(!d.isEmpty()&&!d.peekFirst().isAfter(now.minus(Duration.ofDays(1)))) d.pollFirst();
                if(limit.getRpd()>0&&d.size()>=limit.getRpd()) throw new JudgeFailure("JUDGE_"+bucket+"_DAILY_LIMIT");
                if(limit.getRpm()<=0||m.size()<limit.getRpm()) { m.addLast(now);d.addLast(now);return; }
                wait=Duration.between(now,m.peekFirst().plusSeconds(60)).plusMillis(50);
            }
            if(clock.now().plus(wait).isAfter(deadline)) throw new JudgeFailure("JUDGE_"+bucket+"_QUEUE_TIMEOUT");
            try { clock.sleep(wait); }
            catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new JudgeFailure("JUDGE_"+bucket+"_INTERRUPTED"); }
        }
    }
    /** 429 응답을 받은 뒤 기다립니다(재시도 사이). */
    void backoff(Duration wait) {
        try { clock.sleep(wait); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new JudgeFailure("JUDGE_INTERRUPTED"); }
    }
    /** 지난 1분·24시간 사용량(운영 확인용). */
    public synchronized Map<String,Object> snapshot() {
        var out=new LinkedHashMap<String,Object>();var now=clock.now();
        for(var b:Bucket.values()) {
            long m=minute.get(b).stream().filter(t->t.isAfter(now.minusSeconds(60))).count();
            long d=day.get(b).stream().filter(t->t.isAfter(now.minus(Duration.ofDays(1)))).count();
            out.put(b.name(),Map.of("lastMinute",m,"last24h",d,"rpm",limit(b).getRpm(),"rpd",limit(b).getRpd()));
        }
        return out;
    }
}
