package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 판정 호출마다 실제 토큰 수를 학생·용도별로 기록합니다(judge_usage). 비용 확인과 한도 조정에 씁니다.
 * 호출하는 쪽이 open()으로 학생·용도를 정해 두면, SemanticJudgeClient가 응답의 usage 값으로 record()합니다.
 */
@Component
public class JudgeUsage {
    public enum Task { SEMANTIC, PURPOSE, RUBRIC }
    record Context(String evaluationId,String participantId,Task task,Runnable heartbeat) {}
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private final JdbcTemplate db;
    public JudgeUsage(JdbcTemplate db){this.db=db;}

    /** close()가 예외를 던지지 않는 범위. */
    public interface Scope extends AutoCloseable { @Override void close(); }
    /** try-with-resources로 씁니다. 중첩되면 바깥 맥락을 되돌립니다. */
    public Scope open(String evaluationId,String participantId,Task task) {
        return open(evaluationId,participantId,task,()->{});
    }
    Scope open(String evaluationId,String participantId,Task task,Runnable heartbeat) {
        var previous=CURRENT.get();CURRENT.set(new Context(evaluationId,participantId,task,heartbeat));
        return ()->{if(previous==null)CURRENT.remove();else CURRENT.set(previous);};
    }
    void touchJob() {
        var context=CURRENT.get();
        if(context!=null)context.heartbeat().run();
    }
    /** 맥락이 없으면(예: 시험 호출) 기록하지 않습니다. 기록 실패는 판정을 막지 않습니다. */
    void record(String provider,String model,long input,long output,long thinking) {
        var c=CURRENT.get();if(c==null) return;
        try {
            db.update("INSERT INTO judge_usage(evaluation_id,participant_id,task,provider,model,input_tokens,output_tokens,thinking_tokens,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    c.evaluationId(),c.participantId(),c.task().name(),provider,model,input,output,thinking,OffsetDateTime.now(ZoneOffset.UTC));
        } catch(RuntimeException ignored) {}
    }
    public record Row(String participantId,String nickname,String task,String provider,long calls,long inputTokens,long outputTokens,long thinkingTokens) {}
    public record Summary(String evaluationId,List<Row> rows,long calls,long inputTokens,long outputTokens,long thinkingTokens) {}
    public Summary summary(String evaluationId) {
        var rows=db.query("SELECT u.participant_id,p.nickname,u.task,u.provider,COUNT(*) calls,SUM(u.input_tokens) i,SUM(u.output_tokens) o,SUM(u.thinking_tokens) t "+
                "FROM judge_usage u LEFT JOIN participants p ON p.id=u.participant_id WHERE u.evaluation_id=? GROUP BY u.participant_id,p.nickname,u.task,u.provider ORDER BY p.nickname,u.task,u.provider",
                (r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getLong(6),r.getLong(7),r.getLong(8)),evaluationId);
        return new Summary(evaluationId,rows,rows.stream().mapToLong(Row::calls).sum(),rows.stream().mapToLong(Row::inputTokens).sum(),
                rows.stream().mapToLong(Row::outputTokens).sum(),rows.stream().mapToLong(Row::thinkingTokens).sum());
    }
}
