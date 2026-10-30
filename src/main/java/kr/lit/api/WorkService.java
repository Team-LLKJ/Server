package kr.lit.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static kr.lit.api.WorkModels.*;

@Service
@Transactional
public class WorkService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public WorkService(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }

    private ApiError.Failure failure(HttpStatus status, String code, String message) {
        return new ApiError.Failure(status, code, message);
    }
    private String encode(Object object) {
        try { return json.writeValueAsString(object); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize stored data", ex); }
    }
    private JsonNode decode(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid stored JSON", ex); }
    }
    private Instant instant(ResultSet rs,String column) throws SQLException {
        OffsetDateTime value=rs.getObject(column,OffsetDateTime.class);
        return value==null?null:value.toInstant();
    }
    private Context evaluation(String id) {
        var rows=db.query("SELECT * FROM evaluations WHERE id=? FOR UPDATE",(r,n)->
                new Context(id,null,r.getString("state"),instant(r,"expires_at")),id);
        if(rows.isEmpty()) throw failure(HttpStatus.NOT_FOUND,"EVALUATION_NOT_FOUND","평가를 찾을 수 없습니다.");
        kr.lit.auth.AccountAccess.checkTeacherOwner(db.queryForObject("SELECT teacher_id FROM evaluations WHERE id=?",String.class,id));
        return rows.get(0);
    }
    private Context authenticate(String id,String token) {
        var e=evaluation(id);
        var guest=new kr.lit.auth.ParticipantAccess(db).authenticate(id,token);
        return new Context(id,guest.participantId(),e.state(),e.expiresAt());
    }

    private boolean submitted(Context c) {
        return db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,c.participantId())>0;
    }
    private void started(Context c) {
        if(c.state().equals("WAITING")) throw failure(HttpStatus.CONFLICT,"EVALUATION_NOT_STARTED","평가가 아직 시작되지 않았습니다.");
    }
    private void editable(Context c) {
        if(submitted(c)) throw failure(HttpStatus.CONFLICT,"ALREADY_SUBMITTED","제출 확정 후에는 변경할 수 없습니다.");
        started(c);
        if(c.state().equals("ENDED")) throw failure(HttpStatus.CONFLICT,"EVALUATION_ENDED","종료된 평가입니다.");
        if(!c.expiresAt().isAfter(Instant.now())) throw failure(HttpStatus.GONE,"PIN_EXPIRED","만료된 평가입니다.");
    }
    private AiPolicy policy(String evaluationId) {
        var settings=db.queryForList("SELECT ai_policy FROM evaluation_settings WHERE evaluation_id=?",String.class,evaluationId);
        return settings.isEmpty()?AiPolicy.DISABLED:AiPolicy.valueOf(settings.get(0));
    }
    private List<Question> questions(String id) {
        return db.query("SELECT * FROM questions WHERE evaluation_id=? ORDER BY position,id",(r,n)->
                new Question(r.getString("id"),r.getInt("position"),r.getString("title"),r.getString("content")),id);
    }
    private Question question(String id,String questionId) {
        return questions(id).stream().filter(q->q.id().equals(questionId)).findFirst()
                .orElseThrow(()->failure(HttpStatus.NOT_FOUND,"QUESTION_NOT_FOUND","이 평가의 문항을 찾을 수 없습니다."));
    }
    private Answer answer(String participantId,String questionId) {
        var answers=db.query("SELECT * FROM answers WHERE participant_id=? AND question_id=?",(r,n)->
                new Answer(questionId,r.getString("content"),r.getInt("version"),instant(r,"updated_at")),participantId,questionId);
        return answers.isEmpty()?new Answer(questionId,"",0,null):answers.get(0);
    }
    private List<QuestionAnswer> answers(Context c) {
        return questions(c.evaluationId()).stream().map(q->new QuestionAnswer(q,answer(c.participantId(),q.id()))).toList();
    }
    private long event(Context c,String questionId,String type,Object details) {
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO learning_events(evaluation_id,participant_id,question_id,event_type,details,created_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",new String[]{"id"});
            statement.setString(1,c.evaluationId());statement.setString(2,c.participantId());
            statement.setString(3,questionId);statement.setString(4,type);statement.setString(5,encode(details));
            return statement;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public Configuration configure(String id,Configure config) {
        var e=evaluation(id);
        if(!e.state().equals("WAITING")) throw failure(HttpStatus.CONFLICT,"CONFIGURATION_LOCKED","시작 후에는 문항과 AI 정책을 변경할 수 없습니다.");
        if(config.questions().stream().map(QuestionInput::position).distinct().count()!=config.questions().size())
            throw failure(HttpStatus.BAD_REQUEST,"DUPLICATE_POSITION","문항 순서는 중복될 수 없습니다.");
        db.update("DELETE FROM questions WHERE evaluation_id=?",id);
        for(var q:config.questions()) db.update("INSERT INTO questions VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),id,q.position(),q.title().strip(),q.content());
        db.update("MERGE INTO evaluation_settings(evaluation_id,ai_policy) KEY(evaluation_id) VALUES (?,?)",id,config.aiPolicy().name());
        if(config.guidelines()!=null) {
            if(!e.expiresAt().isAfter(Instant.now())) throw failure(HttpStatus.GONE,"PIN_EXPIRED","만료된 평가입니다.");
            db.update("UPDATE evaluations SET evaluation_content=? WHERE id=?",config.guidelines(),id);
        }
        return new Configuration(id,config.aiPolicy(),questions(id),guidelines(id));
    }
    public Configuration configuration(String id) {
        evaluation(id);return new Configuration(id,policy(id),questions(id),guidelines(id));
    }
    private String guidelines(String id) {
        return db.queryForObject("SELECT evaluation_content FROM evaluations WHERE id=?",String.class,id);
    }
    public Workspace workspace(String id,String token) {
        var c=authenticate(id,token);started(c);
        String status=submitted(c)?"SUBMITTED":c.state().equals("ENDED")?"ENDED":!c.expiresAt().isAfter(Instant.now())?"EXPIRED":"IN_PROGRESS";
        return new Workspace(id,c.participantId(),status,!status.equals("IN_PROGRESS"),policy(id),answers(c),guidelines(id),guidelines(id));
    }
    public QuestionAnswer select(String id,String token,String questionId) {
        var c=authenticate(id,token);started(c);
        return new QuestionAnswer(question(id,questionId),answer(c.participantId(),questionId));
    }
    public Answer save(String id,String token,String questionId,SaveAnswer input) {
        var c=authenticate(id,token);editable(c);question(id,questionId);
        String safeContent=AnswerHtml.clean(input.content());
        var old=answer(c.participantId(),questionId);
        if(old.version()!=input.expectedVersion()) throw failure(HttpStatus.CONFLICT,"ANSWER_VERSION_CONFLICT","답안이 변경되었습니다. 최신 답안을 조회한 후 다시 저장해주세요.");
        if(input.source()!=AnswerSource.DIRECT) {
            if(input.aiRequestId()==null||input.aiRequestId().isBlank()) throw failure(HttpStatus.BAD_REQUEST,"AI_REFERENCE_REQUIRED","AI 사용 답안은 참고한 대화 요청 ID가 필요합니다.");
            var refs=db.queryForList("SELECT id FROM ai_turns WHERE participant_id=? AND question_id=? AND request_id=? AND state='COMPLETED'",Long.class,c.participantId(),questionId,input.aiRequestId());
            if(refs.isEmpty()) throw failure(HttpStatus.BAD_REQUEST,"INVALID_AI_REFERENCE","본인의 해당 문항에 완료된 AI 대화가 필요합니다.");
        } else if(input.aiRequestId()!=null) {
            throw failure(HttpStatus.BAD_REQUEST,"INVALID_AI_REFERENCE","직접 작성에는 AI 대화 ID를 지정하지 않습니다.");
        }
        var timestamp=OffsetDateTime.now(ZoneOffset.UTC);
        db.update("MERGE INTO answers(participant_id,question_id,content,version,updated_at) KEY(participant_id,question_id) VALUES (?,?,?,?,?)",c.participantId(),questionId,safeContent,old.version()+1,timestamp);
        var change=new LinkedHashMap<String,Object>();
        change.put("previousContent",old.content());change.put("content",safeContent);
        change.put("previousVersion",old.version());change.put("version",old.version()+1);
        change.put("source",input.source());change.put("aiRequestId",input.aiRequestId());
        event(c,questionId,"ANSWER_SAVED",change);
        return answer(c.participantId(),questionId);
    }
    public Event recordClientEvent(String id,String token,ClientEvent input) {
        var c=authenticate(id,token);editable(c);
        if(input.questionId()!=null) question(id,input.questionId());
        Instant now=Instant.now();
        if(input.occurredAt().isAfter(now.plusSeconds(300))||input.occurredAt().isBefore(now.minusSeconds(86400)))
            throw failure(HttpStatus.BAD_REQUEST,"INVALID_EVENT_TIME","이벤트 시각을 확인해주세요.");
        int count=db.queryForObject("SELECT COUNT(*) FROM learning_events WHERE participant_id=? AND event_type IN ('WINDOW_LEAVE','SHORTCUT_CAPTURE') AND created_at>=?",Integer.class,c.participantId(),OffsetDateTime.ofInstant(now.minusSeconds(60),ZoneOffset.UTC));
        if(count>=60) throw failure(HttpStatus.TOO_MANY_REQUESTS,"EVENT_RATE_LIMITED","이벤트 요청이 많습니다. 잠시 후 다시 시도해주세요.");
        var details=Map.of("occurredAt",input.occurredAt().toString(),"clientReported",true);
        long eventId=event(c,input.questionId(),input.type().name(),details);
        return db.queryForObject("SELECT * FROM learning_events WHERE id=?",(r,n)->new Event(r.getLong("id"),r.getString("question_id"),r.getString("event_type"),decode(r.getString("details")),instant(r,"created_at")),eventId);
    }
    private Turn turn(ResultSet r,int n) throws SQLException {
        return new Turn(r.getLong("id"),r.getString("request_id"),r.getString("question_id"),r.getString("question_text"),
                r.getString("answer_text"),r.getString("state"),r.getString("error_code"),r.getString("provider"),r.getString("model"),
                AiPolicy.valueOf(r.getString("ai_policy")),r.getInt("answer_version"),instant(r,"created_at"),instant(r,"completed_at"));
    }
    private List<Turn> turns(Context c) {
        return db.query("SELECT * FROM ai_turns WHERE participant_id=? ORDER BY id",this::turn,c.participantId());
    }
    public List<Turn> conversations(String id,String token,String questionId,long afterId,int limit) {
        var c=authenticate(id,token);started(c);
        if(questionId!=null) question(id,questionId);
        return turns(c).stream().filter(t->t.id()>afterId&&(questionId==null||t.questionId().equals(questionId))).limit(limit).toList();
    }
    private List<Event> events(Context c) {
        return db.query("SELECT * FROM learning_events WHERE participant_id=? ORDER BY id",(r,n)->
                new Event(r.getLong("id"),r.getString("question_id"),r.getString("event_type"),decode(r.getString("details")),instant(r,"created_at")),c.participantId());
    }
    public List<Event> learningEvents(String id,String token,long afterId,int limit) {
        var c=authenticate(id,token);started(c);
        return events(c).stream().filter(e->e.id()>afterId).limit(limit).toList();
    }
    public List<Event> teacherEvents(String id,String participantId,long afterId,int limit) {
        var e=evaluation(id);
        if(db.queryForObject("SELECT COUNT(*) FROM participants WHERE id=? AND evaluation_id=?",Integer.class,participantId,id)==0)
            throw failure(HttpStatus.NOT_FOUND,"PARTICIPANT_NOT_FOUND","해당 평가의 참가자가 아닙니다.");
        return events(new Context(id,participantId,e.state(),e.expiresAt())).stream().filter(v->v.id()>afterId).limit(limit).toList();
    }
    private Review review(Context c) {
        var content=new ReviewContent(c.evaluationId(),c.participantId(),policy(c.evaluationId()),answers(c),turns(c),events(c));
        if(content.answers().isEmpty()) throw failure(HttpStatus.CONFLICT,"NO_QUESTIONS","문항이 등록되지 않았습니다.");
        String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(content).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        return new Review(digest,content.conversations().stream().noneMatch(t->t.state().equals("PENDING")),
                content.answers().stream().filter(a->a.answer().content().isBlank()).map(a->a.question().id()).toList(),content);
    }
    public Review review(String id,String token) {
        var c=authenticate(id,token);editable(c);return review(c);
    }
    public Submission submit(String id,String token,String reviewToken) {
        var c=authenticate(id,token);
        if(submitted(c)) {
            String previous=db.queryForObject("SELECT review_token FROM submissions WHERE participant_id=?",String.class,c.participantId());
            if(!Objects.equals(previous,reviewToken)) throw failure(HttpStatus.CONFLICT,"ALREADY_SUBMITTED","이미 제출한 평가입니다.");
            return submission(c);
        }
        editable(c);var review=review(c);
        if(!review.canConfirm()) throw failure(HttpStatus.CONFLICT,"AI_IN_PROGRESS","AI 응답 완료 후 제출해주세요.");
        if(!review.reviewToken().equals(reviewToken)) throw failure(HttpStatus.CONFLICT,"REVIEW_CHANGED","답안 또는 대화가 변경되었습니다. 제출 내용을 다시 확인해주세요.");
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        db.update("INSERT INTO submissions VALUES (?,?,?,?,?)",c.participantId(),id,reviewToken,encode(review.content()),now);
        event(c,null,"SUBMITTED",Map.of("reviewToken",reviewToken));
        return submission(c);
    }
    private Submission submission(Context c) {
        var records=db.query("SELECT * FROM submissions WHERE participant_id=?",(r,n)->
                new Submission("SUBMITTED",instant(r,"submitted_at"),decode(r.getString("snapshot"))),c.participantId());
        if(records.isEmpty()) throw failure(HttpStatus.NOT_FOUND,"SUBMISSION_NOT_FOUND","아직 제출하지 않았습니다.");
        return records.get(0);
    }
    public Submission submission(String id,String token) { return submission(authenticate(id,token)); }

    public Reservation reserve(String id,String token,String questionId,Ask ask,String provider,String model) {
        var c=authenticate(id,token);var q=question(id,questionId);
        var existing=db.query("SELECT * FROM ai_turns WHERE participant_id=? AND request_id=?",this::turn,c.participantId(),ask.requestId());
        if(!existing.isEmpty()) {
            var turn=existing.get(0);
            if(!turn.questionId().equals(questionId)||!turn.questionText().equals(ask.text()))
                throw failure(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED","동일한 requestId로 다른 질문을 보낼 수 없습니다.");
            return new Reservation(c,turn,q,answer(c.participantId(),questionId),List.of(),false);
        }
        editable(c);var policy=policy(id);
        if(policy==AiPolicy.DISABLED) throw failure(HttpStatus.FORBIDDEN,"AI_DISABLED","이 평가에서는 AI 질문이 허용되지 않습니다.");
        var history=turns(c);
        if(history.stream().anyMatch(t->t.state().equals("PENDING"))) throw failure(HttpStatus.CONFLICT,"AI_IN_PROGRESS","진행 중인 AI 질문이 있습니다.");
        if(history.stream().filter(t->t.createdAt().isAfter(Instant.now().minusSeconds(60))).count()>=10)
            throw failure(HttpStatus.TOO_MANY_REQUESTS,"AI_RATE_LIMITED","AI 질문은 1분에 10회까지 가능합니다.");
        if(history.size()>=100) throw failure(HttpStatus.TOO_MANY_REQUESTS,"AI_LIMIT_REACHED","평가당 AI 질문은 최대 100회입니다.");
        var draft=answer(c.participantId(),questionId);
        db.update("INSERT INTO ai_turns(request_id,evaluation_id,participant_id,question_id,question_text,state,provider,model,ai_policy,answer_version,created_at) VALUES (?,?,?,?,?,'PENDING',?,?,?,?,CURRENT_TIMESTAMP)",
                ask.requestId(),id,c.participantId(),questionId,ask.text(),provider,model,policy.name(),draft.version());
        var turn=db.query("SELECT * FROM ai_turns WHERE participant_id=? AND request_id=?",this::turn,c.participantId(),ask.requestId()).get(0);
        event(c,questionId,"AI_REQUESTED",Map.of("requestId",ask.requestId(),"question",ask.text(),"provider",provider,"model",model,"aiPolicy",policy,"answerVersion",draft.version()));
        return new Reservation(c,turn,q,draft,history.stream().filter(t->t.questionId().equals(questionId)&&t.state().equals("COMPLETED")).toList(),true);
    }
    public Turn finish(Reservation reservation,String response,String error) {
        var c=reservation.context();evaluation(c.evaluationId());
        var current=db.query("SELECT * FROM ai_turns WHERE id=?",this::turn,reservation.turn().id()).get(0);
        if(!current.state().equals("PENDING")) return current;
        // Ended/expired evaluations cannot receive a late successful AI turn.
        var e=evaluation(c.evaluationId());
        if(error==null&&(!e.state().equals("STARTED")||!e.expiresAt().isAfter(Instant.now()))) error="EVALUATION_CLOSED";
        boolean success=error==null;
        db.update("UPDATE ai_turns SET state=?,answer_text=?,error_code=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",success?"COMPLETED":"FAILED",success?response:null,error,current.id());
        var details=new LinkedHashMap<String,Object>();details.put("requestId",current.requestId());
        details.put("answer",success?response:null);details.put("errorCode",error);details.put("provider",current.provider());details.put("model",current.model());
        event(c,current.questionId(),success?"AI_COMPLETED":"AI_FAILED",details);
        return db.query("SELECT * FROM ai_turns WHERE id=?",this::turn,current.id()).get(0);
    }
    public void expirePending() {
        // Recover persisted requests left pending by a server crash; never resend them automatically.
        var ids=db.queryForList("SELECT DISTINCT evaluation_id FROM ai_turns WHERE state='PENDING' AND created_at<DATEADD('SECOND',-120,CURRENT_TIMESTAMP)",String.class);
        for(String id:ids) {
            evaluation(id);
            var stale=db.query("SELECT * FROM ai_turns WHERE evaluation_id=? AND state='PENDING' AND created_at<DATEADD('SECOND',-120,CURRENT_TIMESTAMP)",this::turn,id);
            for(var t:stale) {
                String person=db.queryForObject("SELECT participant_id FROM ai_turns WHERE id=?",String.class,t.id());
                db.update("UPDATE ai_turns SET state='FAILED',error_code='AI_REQUEST_ABANDONED',completed_at=CURRENT_TIMESTAMP WHERE id=?",t.id());
                event(new Context(id,person,"STARTED",Instant.now()),t.questionId(),"AI_FAILED",Map.of("requestId",t.requestId(),"errorCode","AI_REQUEST_ABANDONED"));
            }
        }
    }
}
