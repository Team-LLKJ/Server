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
    private final AutomaticDisclosureService automaticDisclosure;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final NotificationService notifications;
    private final int endGraceSeconds;
    private final jakarta.validation.Validator validator;
    private final ProcessGradingRepository processGrading;
    private final ParticipantSessionService sessions;
    public WorkService(JdbcTemplate db, ObjectMapper json, NotificationService notifications,
                       @org.springframework.beans.factory.annotation.Value("${lit.evaluation.end-grace-seconds:0}") int endGraceSeconds,jakarta.validation.Validator validator,ProcessGradingRepository processGrading,ParticipantSessionService sessions,AutomaticDisclosureService automaticDisclosure) {
        this.automaticDisclosure=automaticDisclosure;
        this.db=db; this.json=json; this.notifications=notifications; this.endGraceSeconds=Math.max(0,endGraceSeconds);this.validator=validator;this.processGrading=processGrading;this.sessions=sessions;
    }

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
        kr.lit.auth.AssessmentAccess.beforeEvaluation(db,id);
        var rows=db.query("SELECT * FROM evaluations WHERE id=? FOR UPDATE",(r,n)->
                new Context(id,null,r.getString("state"),instant(r,r.getObject("ends_at")==null?"expires_at":"ends_at")),id);
        if(rows.isEmpty()) throw failure(HttpStatus.NOT_FOUND,"EVALUATION_NOT_FOUND","평가를 찾을 수 없습니다.");
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
                new Question(r.getString("id"),r.getInt("position"),r.getString("title"),r.getString("content"),r.getInt("min_chars")==0?null:r.getInt("min_chars"),(Integer)r.getObject("max_chars")),id);
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
        ClassSnapshots.requireStandalone(db,id);
        AiPolicy resolved=config.resolvedPolicy();
        if(resolved==null) throw failure(HttpStatus.BAD_REQUEST,"INVALID_AI_POLICY","aiPolicy와 aiLevel을 함께 보내면 같은 단계여야 합니다.");
        if(!e.state().equals("WAITING")) throw failure(HttpStatus.CONFLICT,"CONFIGURATION_LOCKED","시작 후에는 문항과 AI 정책을 변경할 수 없습니다.");
        if((config.guidelines()!=null||config.durationMinutes()!=null)&&!e.expiresAt().isAfter(Instant.now()))
            throw failure(HttpStatus.GONE,"PIN_EXPIRED","만료된 평가입니다.");
        QuestionLimits.validate(config.questions());
        var criteria=CommonSettingsRules.criteria(json,validator,config.criteria());
        var antiCheat=config.antiCheat()==null?null:CommonSettingsRules.antiCheat(json,config.antiCheat());
        db.update("DELETE FROM questions WHERE evaluation_id=?",id);
        for(var q:config.questions()) db.update("INSERT INTO questions(id,evaluation_id,position,title,content,min_chars,max_chars) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,q.position(),q.title().strip(),q.content(),q.minChars()==null?0:q.minChars(),q.maxChars());
        db.update("MERGE INTO evaluation_settings(evaluation_id,ai_policy) KEY(evaluation_id) VALUES (?,?)",id,resolved.name());
        if(config.guidelines()!=null) {
            db.update("UPDATE evaluations SET evaluation_content=? WHERE id=?",Guidelines.clean(config.guidelines()),id);
        }
        if(config.durationMinutes()!=null) db.update("UPDATE evaluations SET duration_minutes=? WHERE id=?",config.durationMinutes(),id);
        if(config.criteria()!=null) {
            db.update("UPDATE evaluations SET criteria_json=?,criteria_kind=? WHERE id=?",encode(criteria),CommonSettingsRules.kind(criteria),id);
            CommonSettingsRules.saveRubric(db,json,id,criteria);
        }
        if(config.antiCheat()!=null)db.update("UPDATE evaluations SET anti_cheat_json=? WHERE id=?",encode(antiCheat),id);
        return configuration(id);
    }
    public void saveCreationSettings(String id,List<?> inputCriteria,JsonNode inputAntiCheat) {
        evaluation(id);
        var criteria=CommonSettingsRules.criteria(json,validator,inputCriteria);
        var antiCheat=CommonSettingsRules.antiCheat(json,inputAntiCheat);
        if(inputCriteria!=null){db.update("UPDATE evaluations SET criteria_json=?,criteria_kind=? WHERE id=?",encode(criteria),CommonSettingsRules.kind(criteria),id);CommonSettingsRules.saveRubric(db,json,id,criteria);}
        if(inputAntiCheat!=null)db.update("UPDATE evaluations SET anti_cheat_json=? WHERE id=?",encode(antiCheat),id);
    }
    public Configuration configuration(String id) {
        evaluation(id);var p=policy(id);var shared=shared(id);
        return new Configuration(id,p,questions(id),guidelines(id),durationMinutes(id),p.level(),levelDescription(p),shared.criteria(),shared.antiCheat(),shared.assessmentId(),shared.className());
    }
    private record Shared(List<?> criteria,JsonNode antiCheat,String assessmentId,String className) {}
    private Shared shared(String id) {
        return db.queryForObject("SELECT criteria_json,criteria_kind,anti_cheat_json,assessment_id,class_name FROM evaluations WHERE id=?",(r,n)->{
            try {
                List<?> criteria=json.readValue(r.getString("criteria_json"),new com.fasterxml.jackson.core.type.TypeReference<List<Object>>(){});
                // 기존 사용자 지정 루브릭도 새 configuration 응답에서 읽을 수 있게 합니다.
                if(criteria.isEmpty()&&r.getString("criteria_kind").equals("LEGACY")) {
                    var old=db.queryForList("SELECT criteria_json FROM evaluation_rubrics WHERE evaluation_id=?",String.class,id);
                    if(!old.isEmpty()) {
                        List<AssessmentModels.Criterion> rubric=json.readValue(old.get(0),new com.fasterxml.jackson.core.type.TypeReference<List<AssessmentModels.Criterion>>(){});
                        criteria=rubric.stream().map(c->new AssessmentModels.CriterionInput(c.name(),c.maxScore(),c.description(),c.levelDescriptions(),c.indicator())).toList();
                    }
                }
                return new Shared(criteria,decode(r.getString("anti_cheat_json")),r.getString("assessment_id"),r.getString("class_name"));
            }catch(JsonProcessingException ex){throw new IllegalStateException(ex);}
        },id);
    }
    private int durationMinutes(String id) {return db.queryForObject("SELECT duration_minutes FROM evaluations WHERE id=?",Integer.class,id);}
    private String guidelines(String id) {
        return db.queryForObject("SELECT evaluation_content FROM evaluations WHERE id=?",String.class,id);
    }
    public Workspace workspace(String id,String token) {
        var c=authenticate(id,token);started(c);
        finishEvaluation(id,true);c=authenticate(id,token);
        String status=submitted(c)?"SUBMITTED":c.state().equals("ENDED")?"ENDED":!c.expiresAt().isAfter(Instant.now())?"EXPIRED":"IN_PROGRESS";
        var p=policy(id);
        var shared=shared(id);
        var accepted=sessions.getPolicyStatus(id,token);
        return new Workspace(id,c.participantId(),status,!status.equals("IN_PROGRESS"),p,answers(c),guidelines(id),guidelines(id),p.level(),levelDescription(p),shared.criteria(),shared.antiCheat(),shared.assessmentId(),shared.className(),accepted.policyAccepted(),accepted.policyAcceptedAt(),accepted.policyVersion());
    }
    public QuestionAnswer select(String id,String token,String questionId) {
        var c=authenticate(id,token);started(c);
        return new QuestionAnswer(question(id,questionId),answer(c.participantId(),questionId));
    }
    public InputPathAnalyzer.QuestionPath getInputPath(String id,String token,String questionId){
        var context=authenticate(id,token);started(context);question(id,questionId);
        var snapshot=json.valueToTree(Map.of("answers",answers(context),"learningEvents",events(context)));
        return new InputPathAnalyzer().analyzePaths(snapshot).questions().stream().filter(q->q.questionId().equals(questionId)).findFirst().orElseThrow();
    }
    public Answer getAnswer(String id,String token,String questionId) {
        return select(id,token,questionId).answer();
    }
    public Answer save(String id,String token,String questionId,SaveAnswer input) {
        var c=authenticate(id,token);
        // 마감 직후(서버 작업 전)라면 먼저 종료·자동 제출을 처리해 유예 저장 경로로 보냅니다.
        if(endGraceSeconds>0&&c.state().equals("STARTED")&&!c.expiresAt().isAfter(Instant.now())) { finishEvaluation(id,true);c=authenticate(id,token); }
        boolean late=lateSaveAllowed(c);
        if(!late) editable(c);
        var selected=question(id,questionId);
        String safeContent=AnswerHtml.clean(input.content());
        if(selected.maxChars()!=null&&QuestionLimits.count(safeContent)>selected.maxChars())
            throw failure(HttpStatus.BAD_REQUEST,"ANSWER_TOO_LONG","답안이 문항의 최대 글자 수를 초과했습니다.");
        var old=answer(c.participantId(),questionId);
        if(old.version()!=input.expectedVersion()) throw failure(HttpStatus.CONFLICT,"ANSWER_VERSION_CONFLICT","답안이 변경되었습니다. 최신 답안을 조회한 후 다시 저장해주세요.");
        if(input.source()!=AnswerSource.DIRECT) {
            if(input.aiRequestId()==null||input.aiRequestId().isBlank()) throw failure(HttpStatus.BAD_REQUEST,"AI_REFERENCE_REQUIRED","AI 사용 답안은 참고한 대화 요청 ID가 필요합니다.");
            var refs=db.queryForList("SELECT id FROM ai_turns WHERE participant_id=? AND question_id=? AND request_id=? AND state='COMPLETED'",Long.class,c.participantId(),questionId,input.aiRequestId());
            if(refs.isEmpty()) throw failure(HttpStatus.BAD_REQUEST,"INVALID_AI_REFERENCE","본인의 해당 문항에 완료된 AI 대화가 필요합니다.");
        } else if(input.aiRequestId()!=null) {
            throw failure(HttpStatus.BAD_REQUEST,"INVALID_AI_REFERENCE","직접 작성에는 AI 대화 ID를 지정하지 않습니다.");
        }
        if(input.inputOperations()!=null){
            try{
                if(!InputPathAnalyzer.normalizeText(safeContent).equals(InputPathAnalyzer.replayText(InputPathAnalyzer.normalizeText(old.content()),input.inputOperations())))
                    throw new IllegalArgumentException("INPUT_TRACE_MISMATCH");
                for(var operation:input.inputOperations()){
                    if(operation.type()==InputOperationType.AI_INSERT){
                        var responses=db.queryForList("SELECT answer_text FROM ai_turns WHERE id=? AND participant_id=? AND question_id=? AND state='COMPLETED'",String.class,operation.conversationId(),c.participantId(),questionId);
                        if(operation.conversationId()==null||operation.text().isBlank()||responses.isEmpty()||!InputPathAnalyzer.normalizeText(responses.get(0)).contains(operation.text()))throw new IllegalArgumentException("INVALID_AI_INSERT_REFERENCE");
                    }else if(operation.conversationId()!=null)throw new IllegalArgumentException("INVALID_AI_INSERT_REFERENCE");
                }
            }catch(IllegalArgumentException invalid){throw failure(HttpStatus.BAD_REQUEST,invalid.getMessage(),"편집 이력의 구간·버전·결과 텍스트·AI 인용을 확인해주세요.");}
        }
        var timestamp=OffsetDateTime.now(ZoneOffset.UTC);
        db.update("MERGE INTO answers(participant_id,question_id,content,version,updated_at) KEY(participant_id,question_id) VALUES (?,?,?,?,?)",c.participantId(),questionId,safeContent,old.version()+1,timestamp);
        var change=new LinkedHashMap<String,Object>();
        change.put("previousContent",old.content());change.put("content",safeContent);
        change.put("previousVersion",old.version());change.put("version",old.version()+1);
        change.put("source",input.source());change.put("aiRequestId",input.aiRequestId());
        if(input.inputOperations()!=null)change.put("inputOperations",input.inputOperations());
        if(late) change.put("lateSave",true);
        event(c,questionId,"ANSWER_SAVED",change);
        if(late) refreshAutoSubmission(c);
        return answer(c.participantId(),questionId);
    }
    /** 종료 후 유예 시간 동안 자동 제출된(AUTO_*) 참가자의 마지막 저장을 허용합니다. 수동 제출자는 해당하지 않습니다. */
    private boolean lateSaveAllowed(Context c) {
        if(endGraceSeconds<=0||!c.state().equals("ENDED")) return false;
        if(db.queryForObject("SELECT COUNT(*) FROM grading_score_changes WHERE participant_id=?",Integer.class,c.participantId())>0
                ||db.queryForObject("SELECT COUNT(*) FROM teacher_reviews WHERE participant_id=?",Integer.class,c.participantId())>0
                ||db.queryForObject("SELECT COUNT(*) FROM grading_scores WHERE participant_id=?",Integer.class,c.participantId())>0
                ||db.queryForObject("SELECT COUNT(*) FROM usage_reports WHERE participant_id=?",Integer.class,c.participantId())>0)
            return false;
        var rows=db.query("SELECT s.submission_mode,e.ended_at,e.ends_at FROM submissions s JOIN evaluations e ON e.id=s.evaluation_id WHERE s.participant_id=?",
                (r,n)->new Object[]{r.getString("submission_mode"),instant(r,"ended_at"),instant(r,"ends_at")},c.participantId());
        if(rows.isEmpty()) return false;
        String mode=(String)rows.get(0)[0];Instant ended=(Instant)rows.get(0)[1];
        Instant cutoff="AUTO_DEADLINE".equals(mode)&&rows.get(0)[2]!=null?(Instant)rows.get(0)[2]:ended;
        return mode!=null&&mode.startsWith("AUTO_")&&cutoff!=null&&Instant.now().isBefore(cutoff.plusSeconds(endGraceSeconds));
    }
    private void refreshAutoSubmission(Context c) {
        var snapshot=new ReviewContent(c.evaluationId(),c.participantId(),policy(c.evaluationId()),answers(c),turns(c),events(c),snapshotDisclosure(c));
        String content=encode(snapshot);
        db.update("UPDATE submissions SET snapshot=?,review_token=? WHERE participant_id=?",content,sha(content),c.participantId());
        requestAnalysis(c.evaluationId(),c.participantId());
    }
    public int endGraceSeconds() { return endGraceSeconds; }
    private static String sha(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    /** 제출 직후 분석을 예약합니다. GradingAnalysisJob이 처리하며 조회 시에도 즉시 계산됩니다. */
    void requestAnalysis(String evaluationId,String participantId) {
        processGrading.queueSubmission(evaluationId,participantId);
        db.update("MERGE INTO grading_analyses(participant_id,evaluation_id,analysis_status,requested_at) KEY(participant_id) VALUES (?,?,'PENDING',?)",
                participantId,evaluationId,OffsetDateTime.now(ZoneOffset.UTC));
    }
    private String nickname(String participantId) {
        return db.queryForObject("SELECT nickname FROM participants WHERE id=?",String.class,participantId);
    }
    private boolean splitsSurrogate(String text,int at){return at>0&&at<text.length()&&Character.isHighSurrogate(text.charAt(at-1))&&Character.isLowSurrogate(text.charAt(at));}
    public Event recordClientEvent(String id,String token,ClientEvent input) {
        var c=authenticate(id,token);editable(c);
        if(input.questionId()!=null) question(id,input.questionId());
        Instant now=Instant.now();
        if(input.occurredAt().isAfter(now.plusSeconds(300))||input.occurredAt().isBefore(now.minusSeconds(86400)))
            throw failure(HttpStatus.BAD_REQUEST,"INVALID_EVENT_TIME","이벤트 시각을 확인해주세요.");
        int count=db.queryForObject("SELECT COUNT(*) FROM learning_events WHERE participant_id=? AND event_type IN ("+ClientEventType.clientSql()+") AND created_at>=?",Integer.class,c.participantId(),OffsetDateTime.ofInstant(now.minusSeconds(60),ZoneOffset.UTC));
        if(count>=60) throw failure(HttpStatus.TOO_MANY_REQUESTS,"EVENT_RATE_LIMITED","이벤트 요청이 많습니다. 잠시 후 다시 시도해주세요.");
        if(input.conversationId()!=null&&db.queryForObject("SELECT COUNT(*) FROM ai_turns WHERE id=? AND participant_id=?",Integer.class,input.conversationId(),c.participantId())==0)
            throw failure(HttpStatus.NOT_FOUND,"CONVERSATION_NOT_FOUND","본인의 AI 대화가 아닙니다.");
        boolean range=input.start()!=null||input.end()!=null||input.answerVersion()!=null;
        if(range){
            if(input.type()!=ClientEventType.PASTE||input.questionId()==null||input.start()==null||input.end()==null||input.answerVersion()==null||input.start()>input.end())throw failure(HttpStatus.BAD_REQUEST,"INVALID_PASTE_RANGE","붙여넣기 구간·문항·답안 버전을 확인해주세요.");
            var saved=answer(c.participantId(),input.questionId());
            if(saved.version()!=input.answerVersion())throw failure(HttpStatus.CONFLICT,"PASTE_VERSION_CONFLICT","최신 저장 답안 버전으로 전송해주세요.");
            String text=String.join("\n",AssessmentAnalyzer.paragraphs(saved.content()));
            if(input.end()>text.length()||splitsSurrogate(text,input.start())||splitsSurrogate(text,input.end()))throw failure(HttpStatus.BAD_REQUEST,"INVALID_PASTE_RANGE","정규화 답안 텍스트의 UTF-16 구간을 확인해주세요.");
        }
        var details=new LinkedHashMap<String,Object>();
        if(range){details.put("start",input.start());details.put("end",input.end());details.put("answerVersion",input.answerVersion());}
        details.put("occurredAt",input.occurredAt().toString());details.put("clientReported",true);
        details.put("warning",input.type().warning());
        if(input.length()!=null) details.put("length",input.length());
        if(input.conversationId()!=null) details.put("conversationId",input.conversationId());
        long eventId=event(c,input.questionId(),input.type().name(),details);
        if(input.type().warning()) notifications.notifyOwner(id,NotificationService.Type.WARNING,"경고 이벤트 기록",
                nickname(c.participantId())+" 학생에게 창 이탈·캡처·복사 시도·전체화면 이탈·개발자 도구 열림 등 경고 이벤트가 기록되었습니다. 신고 기록만으로 부정행위를 판단하지 않습니다.",
                c.participantId(),"warning:"+c.participantId());
        return db.queryForObject("SELECT * FROM learning_events WHERE id=?",(r,n)->new Event(r.getLong("id"),r.getString("question_id"),r.getString("event_type"),decode(r.getString("details")),instant(r,"created_at")),eventId);
    }
    private DisclosureView snapshotDisclosure(Context c) {
        return automaticDisclosure.getDisclosure(c.evaluationId(),c.participantId());
    }
    public DisclosureView disclosure(String id,String token) {
        var c=authenticate(id,token);return snapshotDisclosure(c);
    }
    /** Compatibility endpoint: client input is deliberately ignored. */
    public DisclosureView saveDisclosure(String id,String token,Disclosure input) {
        return disclosure(id,token);
    }
    private ReviewContent finalizeDisclosure(Context c,ReviewContent content){
        var disclosure=automaticDisclosure.generateDisclosure(c.evaluationId(),c.participantId(),json.valueToTree(content));
        return new ReviewContent(content.evaluationId(),content.participantId(),content.aiPolicy(),content.answers(),content.conversations(),content.learningEvents(),disclosure);
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
        var content=new ReviewContent(c.evaluationId(),c.participantId(),policy(c.evaluationId()),answers(c),turns(c),events(c),snapshotDisclosure(c));
        if(content.answers().isEmpty()) throw failure(HttpStatus.CONFLICT,"NO_QUESTIONS","문항이 등록되지 않았습니다.");
        String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(content).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        var errors=content.answers().stream().map(a->QuestionLimits.error(a.question(),a.answer())).filter(Objects::nonNull).toList();
        return new Review(digest,errors.isEmpty()&&content.conversations().stream().noneMatch(t->t.state().equals("PENDING")),
                content.answers().stream().filter(a->a.answer().content().isBlank()).map(a->a.question().id()).toList(),content,errors);
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
        if(!review.answerValidationErrors().isEmpty())throw failure(HttpStatus.BAD_REQUEST,"ANSWER_LENGTH_INVALID","문항별 최소·최대 글자 수를 확인해주세요.");
        if(!review.canConfirm()) throw failure(HttpStatus.CONFLICT,"AI_IN_PROGRESS","AI 응답 완료 후 제출해주세요.");
        if(!review.reviewToken().equals(reviewToken)) throw failure(HttpStatus.CONFLICT,"REVIEW_CHANGED","답안 또는 대화가 변경되었습니다. 제출 내용을 다시 확인해주세요.");
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        db.update("INSERT INTO submissions(participant_id,evaluation_id,review_token,snapshot,submitted_at) VALUES (?,?,?,?,?)",c.participantId(),id,reviewToken,encode(finalizeDisclosure(c,review.content())),now);
        event(c,null,"SUBMITTED",Map.of("reviewToken",reviewToken));
        requestAnalysis(id,c.participantId());
        notifications.notifyOwner(id,NotificationService.Type.SUBMISSION,"답안 제출",nickname(c.participantId())+" 학생이 답안을 제출했습니다.",
                c.participantId(),"submission:"+c.participantId());
        return submission(c);
    }
    private Submission submission(Context c) {
        var records=db.query("SELECT * FROM submissions WHERE participant_id=?",(r,n)->
                new Submission("SUBMITTED",instant(r,"submitted_at"),decode(r.getString("snapshot")),r.getString("submission_mode")),c.participantId());
        if(records.isEmpty()) throw failure(HttpStatus.NOT_FOUND,"SUBMISSION_NOT_FOUND","아직 제출하지 않았습니다.");
        return records.get(0);
    }
    public Submission submission(String id,String token) {
        authenticate(id,token);finishEvaluation(id,true);return submission(authenticate(id,token));
    }

    public void finishEvaluation(String id,boolean deadlineOnly) {
        var e=evaluation(id);
        if(e.state().equals("ENDED")) return;
        if(deadlineOnly&&(!e.state().equals("STARTED")||e.expiresAt().isAfter(Instant.now()))) return;
        boolean wasStarted=e.state().equals("STARTED");
        Instant now=Instant.now();
        boolean expired=wasStarted&&!e.expiresAt().isAfter(now);
        String mode=expired?"AUTO_DEADLINE":"AUTO_TEACHER_END";
        db.update("UPDATE evaluations SET state='ENDED',ended_at=? WHERE id=?",OffsetDateTime.ofInstant(now,ZoneOffset.UTC),id);
        var pending=db.query("SELECT * FROM ai_turns WHERE evaluation_id=? AND state='PENDING'",this::turn,id);
        for(var turn:pending) {
            String pid=db.queryForObject("SELECT participant_id FROM ai_turns WHERE id=?",String.class,turn.id());
            event(new Context(id,pid,"ENDED",e.expiresAt()),turn.questionId(),"AI_FAILED",Map.of("requestId",turn.requestId(),"errorCode","EVALUATION_CLOSED"));
        }
        db.update("UPDATE ai_turns SET state='FAILED',error_code='EVALUATION_CLOSED',completed_at=CURRENT_TIMESTAMP WHERE evaluation_id=? AND state='PENDING'",id);
        if(!wasStarted){
            for(String pid:db.queryForList("SELECT id FROM participants WHERE evaluation_id=?",String.class,id)){
                var context=new Context(id,pid,"ENDED",e.expiresAt());
                automaticDisclosure.generateDisclosure(id,pid,json.valueToTree(new ReviewContent(id,pid,policy(id),answers(context),turns(context),events(context))));
            }
            return;
        }
        var participants=db.queryForList("SELECT id FROM participants WHERE evaluation_id=? ORDER BY id",String.class,id);
        int autoSubmitted=0;
        for(String pid:participants) {
            var context=new Context(id,pid,"ENDED",e.expiresAt());
            if(submitted(context))continue;
            var snapshot=new ReviewContent(id,pid,policy(id),answers(context),turns(context),events(context),snapshotDisclosure(context));
            snapshot=finalizeDisclosure(context,snapshot);
            String content=encode(snapshot),hash;
            try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));}
            catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
            db.update("INSERT INTO submissions(participant_id,evaluation_id,review_token,snapshot,submitted_at,submission_mode) VALUES (?,?,?,?,?,?)",pid,id,hash,content,OffsetDateTime.ofInstant(now,ZoneOffset.UTC),mode);
            event(context,null,"SUBMITTED",Map.of("reviewToken",hash,"submissionMode",mode));
            requestAnalysis(id,pid);autoSubmitted++;
        }
        String title=db.queryForObject("SELECT title FROM evaluations WHERE id=?",String.class,id);
        notifications.notifyOwner(id,NotificationService.Type.EVALUATION_ENDED,"평가 종료",
                "'"+title+"' 평가가 "+(expired?"제한 시간 만료로":"교사 종료로")+" 종료되었습니다. 미제출 "+autoSubmitted+"명의 최신 저장 답안을 자동 제출했습니다.",
                null,"ended:"+id);
    }

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
