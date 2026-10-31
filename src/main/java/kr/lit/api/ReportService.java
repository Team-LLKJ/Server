package kr.lit.api;

import com.fasterxml.jackson.databind.*;
import kr.lit.auth.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static kr.lit.api.ReportModels.*;

@Service
@Transactional
public class ReportService {
 private final JdbcTemplate db; private final ObjectMapper json; private final EvaluationService evaluations;
 private final ParticipantAccess participants; private final WorkService work;
 public ReportService(JdbcTemplate db,ObjectMapper json,EvaluationService evaluations,ParticipantAccess participants,WorkService work) {
  this.db=db;this.json=json;this.evaluations=evaluations;this.participants=participants;this.work=work;
 }
 private ApiError.Failure fail(HttpStatus status,String code,String message){return new ApiError.Failure(status,code,message);}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private <T> T parse(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
 private void teacher(String id){AccountAccess.require(AuthModels.Role.TEACHER);evaluations.details(id);}
 private void person(String id,String pid){
  if(db.queryForObject("SELECT COUNT(*) FROM participants WHERE evaluation_id=? AND id=?",Integer.class,id,pid)==0)
   throw fail(HttpStatus.NOT_FOUND,"PARTICIPANT_NOT_FOUND","해당 평가의 참가자가 아닙니다.");
 }
 private Instant instant(java.sql.ResultSet r,String column)throws java.sql.SQLException{var t=r.getObject(column,OffsetDateTime.class);return t==null?null:t.toInstant();}
 private Student student(String id,String pid){
  var e=evaluations.details(id);
  return db.query("SELECT p.*,s.submitted_at,(SELECT MAX(version) FROM usage_reports u WHERE u.participant_id=p.id) report_version FROM participants p LEFT JOIN submissions s ON s.participant_id=p.id WHERE p.id=? AND p.evaluation_id=?",(r,n)->{
   boolean active=r.getBoolean("active");boolean connected=active&&r.getObject("last_seen",OffsetDateTime.class).toInstant().isAfter(Instant.now().minusSeconds(30));
   Instant submitted=instant(r,"submitted_at");boolean closed=e.state().equals("ENDED")||e.expiresAt()!=null&&!e.expiresAt().isAfter(Instant.now());
   String status=submitted!=null?"SUBMITTED":closed?"CLOSED_UNSUBMITTED":"NOT_SUBMITTED";
   return new Student(pid,r.getString("nickname"),!active?"LEFT":connected?"CONNECTED":"DISCONNECTED",status,
     submitted!=null?"SUBMITTED":e.state().equals("ENDED")?"ENDED":closed?"EXPIRED":e.state().equals("STARTED")?"IN_PROGRESS":"WAITING",
     submitted==null&&(!connected||closed),submitted,(Integer)r.getObject("report_version"),review(pid)==null?"NOT_REVIEWED":review(pid).compliance().name(),activityWarning(pid)?"WARNING":submitted!=null?"SUBMITTED":"WRITING",activityWarning(pid));
  },pid,id).get(0);
 }
 private boolean activityWarning(String pid){return db.queryForObject("SELECT COUNT(*) FROM learning_events WHERE participant_id=? AND event_type IN ("+WorkModels.ClientEventType.warningSql()+")",Integer.class,pid)>0;}
 public Students students(String id,int page,int size){return students(id,page,size,"");}
 public Students students(String id,int page,int size,String query){
  teacher(id);if(page<0||size<1||size>100||query==null||query.length()>100)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","page는0 이상, size는1~100, query는100자 이하입니다.");
  String filter=" FROM participants p WHERE p.evaluation_id=? AND LOCATE(LOWER(?),LOWER(p.nickname))>0";
  String q=query.strip();
  long count=db.queryForObject("SELECT COUNT(*)"+filter,Long.class,id,q);
  String warning="EXISTS (SELECT 1 FROM learning_events e WHERE e.participant_id=p.id AND e.event_type IN ("+WorkModels.ClientEventType.warningSql()+"))";
  long warnings=db.queryForObject("SELECT COUNT(*)"+filter+" AND "+warning,Long.class,id,q);
  long submitted=db.queryForObject("SELECT COUNT(*)"+filter+" AND NOT "+warning+" AND EXISTS (SELECT 1 FROM submissions s WHERE s.participant_id=p.id)",Long.class,id,q);
  var ids=db.queryForList("SELECT p.id"+filter+" ORDER BY p.nickname,p.id LIMIT ? OFFSET ?",String.class,id,q,size,(long)page*size);
  var items=ids.stream().map(p->student(id,p)).toList();
  return new Students(items,page,size,count,(count+size-1)/size,new Counts(submitted,warnings,count-warnings-submitted),items);
 }
 private JsonNode source(String id,String pid){
  person(id,pid);
  var stored=db.queryForList("SELECT snapshot FROM submissions WHERE participant_id=?",String.class,pid);
  if(!stored.isEmpty())return decode(stored.get(0));
  var root=json.createObjectNode();root.put("evaluationId",id);root.put("participantId",pid);
  var policy=db.queryForList("SELECT ai_policy FROM evaluation_settings WHERE evaluation_id=?",String.class,id);
  root.put("aiPolicy",policy.isEmpty()?"DISABLED":policy.get(0));
  var answers=root.putArray("answers");
  db.query("SELECT q.*,a.content draft,a.version answer_version,a.updated_at FROM questions q LEFT JOIN answers a ON a.question_id=q.id AND a.participant_id=? WHERE q.evaluation_id=? ORDER BY q.position,q.id",r->{
   var item=answers.addObject();var question=item.putObject("question");
   question.put("id",r.getString("id")).put("position",r.getInt("position")).put("title",r.getString("title")).put("content",r.getString("content"));
   question.set("minChars",json.valueToTree(r.getInt("min_chars")==0?null:r.getInt("min_chars")));
   question.set("maxChars",json.valueToTree(r.getObject("max_chars")));
   var a=item.putObject("answer");a.put("questionId",r.getString("id")).put("content",Objects.toString(r.getString("draft"),"")).put("version",r.getInt("answer_version"));
   var updated=instant(r,"updated_at");a.put("updatedAt",updated==null?null:updated.toString());
  },pid,id);
  var turns=root.putArray("conversations");
  db.query("SELECT * FROM ai_turns WHERE participant_id=? ORDER BY id",r->{var t=turns.addObject();
   t.put("requestId",r.getString("request_id")).put("questionId",r.getString("question_id")).put("questionText",r.getString("question_text")).put("answerText",r.getString("answer_text")).put("state",r.getString("state")).put("provider",r.getString("provider")).put("model",r.getString("model")).put("errorCode",r.getString("error_code"));
   t.put("id",r.getLong("id")).put("aiPolicy",r.getString("ai_policy")).put("answerVersion",r.getInt("answer_version")).put("createdAt",instant(r,"created_at").toString());
   var completed=instant(r,"completed_at");t.put("completedAt",completed==null?null:completed.toString());
  },pid);
  root.set("learningEvents",json.valueToTree(work.teacherEvents(id,pid,0,Integer.MAX_VALUE)));
  return root;
 }
 private TeacherReview review(String pid){var values=db.queryForList("SELECT review_json FROM teacher_reviews WHERE participant_id=?",String.class,pid);return values.isEmpty()?null:parse(values.get(0),TeacherReview.class);}
 private Analysis classify(JsonNode source){
  var classified=new ArrayList<Classification>();var missing=new ArrayList<String>();
  for(var t:source.path("conversations")){
   String text=t.path("questionText").asText().toLowerCase(Locale.ROOT);var purposes=new LinkedHashSet<String>();var stages=new LinkedHashSet<String>();
   if(text.matches("(?s).*(아이디어|브레인|발상|idea).*")){purposes.add("IDEATION");stages.add("EXPLORATION");}
   if(text.matches("(?s).*(찾아|검색|자료|출처|조사).*")){purposes.add("RESEARCH");stages.add("EXPLORATION");}
   if(text.matches("(?s).*(구조|개요|목차|정리|구성).*")){purposes.add("STRUCTURING");stages.add("PLANNING");}
   if(text.matches("(?s).*(검토|확인|맞아|맞는|오류|검증).*")){purposes.add("VERIFICATION");stages.add("REVIEW");}
   if(text.matches("(?s).*(수정|고쳐|다듬|개선|피드백|교정|맞춤법).*")){purposes.add("REVISION");stages.add("REVISION");}
   if(text.matches("(?s).*(답안|정답|대신|작성해|써줘|풀어|완성).*")){purposes.add("ANSWER_DRAFTING");stages.add("DRAFTING");}
   if(text.matches("(?s).*(설명|개념|힌트|이해|왜).*")){purposes.add("CONCEPT_HELP");stages.add("EXPLORATION");}
   if(purposes.isEmpty()){purposes.add("UNCLASSIFIED");stages.add("UNCLASSIFIED");}
   classified.add(new Classification(t.path("requestId").asText(),t.path("questionId").asText(),List.copyOf(purposes),List.copyOf(stages),t.path("questionText").asText(),"RULE_MATCH_REVIEW_REQUIRED"));
  }
  if(classified.isEmpty())missing.add("NO_AI_CONVERSATIONS");
  if(source.path("answers").isEmpty())missing.add("NO_QUESTIONS");
  if(java.util.stream.StreamSupport.stream(source.path("answers").spliterator(),false).allMatch(a->a.path("answer").path("content").asText().isBlank()))missing.add("NO_ANSWER_DATA");
  if(source.path("conversations").findValuesAsText("state").stream().anyMatch(s->s.equals("FAILED")))missing.add("FAILED_AI_REQUESTS_PRESENT");
  return new Analysis("KEYWORD_RULES_V1",classified.isEmpty()?"INSUFFICIENT_DATA":"REVIEW_REQUIRED",classified,missing,
    List.of("키워드 기반 분류이며 의미·정답 여부를 판정하지 않습니다.","답안 생성 요청 자체를 부정행위로 판단하지 않습니다. 교사 허용 정책과 함께 검토하세요.","외부 AI 이용, 미저장 입력과 실제 참고 여부는 관측할 수 없습니다.","분류는 복수 목적을 허용하며 점수를 자동 산출하지 않습니다."));
 }
 /** 채점 화면용 원본: 제출 후에는 확정 snapshot, 제출 전에는 최신 저장본. 소유 교사만 조회할 수 있습니다. */
 public JsonNode work(String id,String pid){teacher(id);person(id,pid);return source(id,pid);}
 public Detail detail(String id,String pid){teacher(id);person(id,pid);var src=source(id,pid);return new Detail(student(id,pid),src,classify(src),review(pid));}
 public TeacherReview saveReview(String id,String pid,ReviewInput input){
  teacher(id);person(id,pid);requireSubmitted(pid);
  if(input.ratings().stream().map(Rating::criterion).distinct().count()!=5)throw fail(HttpStatus.BAD_REQUEST,"INVALID_RUBRIC","5개 영역을 각각 한 번씩 입력해주세요.");
  var current=review(pid);int version=current==null?0:current.version();
  if(version!=input.expectedVersion())throw fail(HttpStatus.CONFLICT,"REVIEW_VERSION_CONFLICT","최신 교사 검토를 조회해주세요.");
  var next=new TeacherReview(version+1,input.compliance(),input.ratings(),input.comment(),AccountAccess.current().userId(),Instant.now());
  persistReview(id,pid,current,next);return next;
 }
 public TeacherReview teacherReview(String id,String pid){teacher(id);person(id,pid);return review(pid);}
 public TeacherReview saveCriterion(String id,String pid,Criterion criterion,ScoreInput input){
  teacher(id);person(id,pid);requireSubmitted(pid);
  var current=review(pid);int version=current==null?0:current.version();
  if(input.expectedVersion()!=null&&input.expectedVersion()!=version)throw fail(HttpStatus.CONFLICT,"REVIEW_VERSION_CONFLICT","최신 교사 검토를 조회해주세요.");
  var ratings=new ArrayList<Rating>();
  for(var c:Criterion.values()) {
   var prior=current==null?null:current.ratings().stream().filter(r->r.criterion()==c).findFirst().orElse(null);
   ratings.add(new Rating(c,c==criterion?input.score():prior==null||prior.score()==null?null:prior.score()*20/current.maxScore(),prior==null?"":prior.evidence()));
  }
  var next=new TeacherReview(version+1,current==null?Compliance.NOT_REVIEWED:current.compliance(),ratings,current==null?"":current.comment(),AccountAccess.current().userId(),Instant.now(),20);
  persistReview(id,pid,current,next);return next;
 }
 private void persistReview(String id,String pid,TeacherReview current,TeacherReview next){
  for(var rating:next.ratings()) {
   Integer before=current==null?null:current.ratings().stream().filter(r->r.criterion()==rating.criterion()).map(Rating::score).filter(Objects::nonNull).findFirst().orElse(null);
   int priorMax=current==null?next.maxScore():current.maxScore();
   boolean equal=before==null?rating.score()==null:rating.score()!=null&&before*next.maxScore()==rating.score()*priorMax;
   if(!equal) db.update("INSERT INTO teacher_review_changes(evaluation_id,participant_id,criterion_id,previous_score,score,teacher_id,changed_at,review_version,previous_max_score,max_score) VALUES (?,?,?,?,?,?,?,?,?,?)",id,pid,rating.criterion().name(),before,rating.score(),next.teacherId(),OffsetDateTime.ofInstant(next.reviewedAt(),ZoneOffset.UTC),next.version(),priorMax,next.maxScore());
  }
  db.update("MERGE INTO teacher_reviews(participant_id,evaluation_id,review_json,updated_at) KEY(participant_id) VALUES (?,?,?,CURRENT_TIMESTAMP)",pid,id,encode(next));
 }
 public List<ReviewChange> reviewHistory(String id,String pid,long afterId,int limit){
  teacher(id);person(id,pid);if(afterId<0||limit<1||limit>200)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","이력 조회 범위를 확인해주세요.");
  return db.query("SELECT * FROM teacher_review_changes WHERE participant_id=? AND id>? ORDER BY id LIMIT ?",(r,n)->new ReviewChange(r.getLong("id"),r.getString("criterion_id"),(Integer)r.getObject("previous_score"),(Integer)r.getObject("score"),r.getString("teacher_id"),instant(r,"changed_at"),r.getInt("review_version"),r.getInt("previous_max_score"),r.getInt("max_score")),pid,afterId,limit);
 }
 private void requireSubmitted(String pid){if(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,pid)==0)throw fail(HttpStatus.CONFLICT,"SUBMISSION_REQUIRED","학생 제출 확정 후 리포트를 생성할 수 있습니다.");}
 private UsageReport generate(String id,String pid){
  person(id,pid);requireSubmitted(pid);var e=evaluations.details(id);var src=source(id,pid);
  String snapshotHash=db.queryForObject("SELECT review_token FROM submissions WHERE participant_id=?",String.class,pid);
  var content=new ReportContent(id,e.title(),pid,student(id,pid).nickname(),student(id,pid).submittedAt(),snapshotHash,src,classify(src),review(pid),review(pid)!=null&&review(pid).maxScore()==20?"PROVISIONAL_FIVE_CRITERIA_0_TO_20":"PROVISIONAL_FIVE_CRITERIA_0_TO_4",
    List.of("5개 영역과 배점은 임시 기준입니다. 최종 평가 기준은 별도 확정이 필요합니다.","점수·허용 규칙 준수 여부는 교사 검토 결과이며 자동 채점이 아닙니다.","기록 부족은 0점이 아닙니다. 검토 전 점수는 null입니다.","AI 탐지율과 정답 여부·부정행위 판정을 제공하지 않습니다."));
  String hash=AuthService.hash(encode(content));
  var existing=db.queryForList("SELECT id FROM usage_reports WHERE participant_id=? AND source_hash=?",String.class,pid,hash);
  if(!existing.isEmpty())return getReport(id,pid,null,existing.get(0));
  int version=db.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM usage_reports WHERE participant_id=?",Integer.class,pid);
  String rid=UUID.randomUUID().toString();Instant now=Instant.now();
  var report=new UsageReport(rid,version,now,content);
  db.update("INSERT INTO usage_reports VALUES (?,?,?,?,?,?,?)",rid,id,pid,version,hash,encode(report),OffsetDateTime.ofInstant(now,ZoneOffset.UTC));return report;
 }
 private UsageReport getReport(String id,String pid,Integer version,String rid){
  person(id,pid);
  var values=rid!=null?db.queryForList("SELECT content FROM usage_reports WHERE participant_id=? AND id=?",String.class,pid,rid):version!=null?
    db.queryForList("SELECT content FROM usage_reports WHERE participant_id=? AND version=?",String.class,pid,version):
    db.queryForList("SELECT content FROM usage_reports WHERE participant_id=? ORDER BY version DESC LIMIT 1",String.class,pid);
  if(values.isEmpty())throw fail(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND","리포트를 찾을 수 없습니다.");return parse(values.get(0),UsageReport.class);
 }
 public UsageReport teacherGenerate(String id,String pid){teacher(id);return generate(id,pid);}
 public UsageReport teacherReport(String id,String pid,Integer version){teacher(id);return getReport(id,pid,version,null);}
 public List<ReportVersion> versions(String id,String pid){teacher(id);person(id,pid);return db.query("SELECT id,version,created_at FROM usage_reports WHERE participant_id=? ORDER BY version DESC",(r,n)->new ReportVersion(r.getString("id"),r.getInt("version"),instant(r,"created_at")),pid);}
 public UsageReport studentGenerate(String id,String token){String pid=participants.authenticate(id,token).participantId();evaluations.details(id);return generate(id,pid);}
 public UsageReport studentReport(String id,String token,Integer version){String pid=participants.authenticate(id,token).participantId();evaluations.details(id);return getReport(id,pid,version,null);}
 // Called by the scheduled job through the Spring proxy, one transaction per participant.
 public void generateForJob(String id,String pid){generate(id,pid);}
}
