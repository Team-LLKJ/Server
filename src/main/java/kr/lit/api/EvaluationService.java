package kr.lit.api;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
import java.security.SecureRandom;
import static kr.lit.api.ApiError.*;
@Service
@Transactional
public class EvaluationService {
 private final JdbcTemplate db;
 private final String baseUrl;
 private final WorkService work;
 private final SecureRandom random=new SecureRandom();
 public EvaluationService(JdbcTemplate db,@Value("${lit.join-base-url}") String baseUrl,WorkService work){this.db=db;this.baseUrl=baseUrl;this.work=work;}
 public record Evaluation(String id,String title,String pin,String state,Instant expiresAt,String assessmentId,String className){}
 public record EvaluationPage(List<Evaluation> items,int page,int size,long totalElements,long totalPages){}
 public record Participant(String id,String nickname,String presence){}
 public record Lobby(String evaluationId,String title,String state,String screen,int participantCount,List<Participant> participants,Instant endsAt,Instant serverNow){}
 public record Created(String id,String title,String pin,String state,Instant expiresAt,String joinUrl,String qrUrl){}
 public record Joined(String participantId,String participantToken,Lobby lobby){}
 private Failure fail(HttpStatus status,String code,String message){return new Failure(status,code,message);}
 private Evaluation read(String id){
        kr.lit.auth.AssessmentAccess.beforeEvaluation(db,id);
  var rows=db.query("SELECT * FROM evaluations WHERE id=? FOR UPDATE",(r,n)->new Evaluation(r.getString("id"),r.getString("title"),r.getString("pin"),r.getString("state"),r.getObject("expires_at",OffsetDateTime.class)==null?null:r.getObject("expires_at",OffsetDateTime.class).toInstant(),r.getString("assessment_id"),r.getString("class_name")),id);
  if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"EVALUATION_NOT_FOUND","평가를 찾을 수 없습니다.");
  return rows.get(0);
 }
 private Evaluation byPin(String pin){
  if(pin==null||!pin.strip().matches("[0-9]{6}"))throw fail(HttpStatus.BAD_REQUEST,"INVALID_PIN","PIN은 6자리 숫자 문자열이어야 합니다.");
  var ids=db.queryForList("SELECT id FROM evaluations WHERE pin=?",String.class,pin.strip());
  if(ids.isEmpty())throw fail(HttpStatus.NOT_FOUND,"PIN_NOT_FOUND","올바르지 않은 PIN입니다.");
  return read(ids.get(0));
 }
 private void available(Evaluation e){
  if(e.state().equals("ENDED"))throw fail(HttpStatus.CONFLICT,"EVALUATION_ENDED","종료된 평가입니다.");
  if(e.pin()==null)throw fail(HttpStatus.CONFLICT,"CLASS_NOT_STARTED","대기실을 연 후 PIN으로 참여할 수 있습니다.");
  if(!e.expiresAt().isAfter(Instant.now()))throw fail(HttpStatus.GONE,"PIN_EXPIRED","만료된 평가입니다.");
 }
 public Created create(String title,int minutes){return create(title,minutes,"");}
 public Created create(String title,int minutes,String evaluationContent){return create(title,minutes,evaluationContent,null);}
 public Created create(String title,int minutes,String evaluationContent,Integer durationMinutes){return create(title,minutes,evaluationContent,durationMinutes,null,null);}
 public Created create(String title,int minutes,String evaluationContent,Integer durationMinutes,List<?> criteria,com.fasterxml.jackson.databind.JsonNode antiCheat){
  String teacherId=kr.lit.auth.AccountAccess.require(kr.lit.auth.AuthModels.Role.TEACHER).userId();
  // The database unique constraint also protects concurrent PIN creation.
  String pin=null;
  for(int i=0;i<100;i++) {String candidate=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
   if(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE pin=?",Integer.class,candidate)==0){pin=candidate;break;}}
  if(pin==null)throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PIN_UNAVAILABLE","PIN을 다시 생성해주세요.");
  String id=UUID.randomUUID().toString(); Instant expiry=Instant.now().plusSeconds(minutes*60L);
  try {db.update("INSERT INTO evaluations(id,title,pin,state,expires_at,teacher_id,evaluation_content,duration_minutes) VALUES (?,?,?,'WAITING',?,?,?,?)",id,title.strip(),pin,OffsetDateTime.ofInstant(expiry,ZoneOffset.UTC),teacherId,evaluationContent==null?"":Guidelines.clean(evaluationContent),durationMinutes==null?minutes:durationMinutes);}
  catch(org.springframework.dao.DuplicateKeyException e){throw fail(HttpStatus.CONFLICT,"PIN_COLLISION","PIN 생성에 실패했습니다. 다시 시도해주세요.");}
  db.update("INSERT INTO evaluation_settings(evaluation_id,ai_policy) VALUES (?,'HINT_ONLY')",id);
  work.saveCreationSettings(id,criteria,antiCheat);
  return new Created(id,title.strip(),pin,"WAITING",expiry,baseUrl+"?pin="+pin,"/api/teacher/evaluations/"+id+"/qr");
 }
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public EvaluationPage mine(int page,int size){
  String teacherId=kr.lit.auth.AccountAccess.require(kr.lit.auth.AuthModels.Role.TEACHER).userId();
  if(page<0||size<1||size>100)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","page는 0 이상, size는 1~100이어야 합니다.");
  long total=db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE teacher_id=?",Long.class,teacherId);
  // Expiry is the existing persisted timestamp; do not present it as creation time.
  var items=db.query("SELECT id,title,pin,state,expires_at,assessment_id,class_name FROM evaluations WHERE teacher_id=? ORDER BY expires_at DESC,id DESC LIMIT ? OFFSET ?",
   (r,n)->new Evaluation(r.getString("id"),r.getString("title"),r.getString("pin"),r.getString("state"),r.getObject("expires_at",OffsetDateTime.class)==null?null:r.getObject("expires_at",OffsetDateTime.class).toInstant(),r.getString("assessment_id"),r.getString("class_name")),teacherId,size,(long)page*size);
  return new EvaluationPage(items,page,size,total,(total+size-1)/size);
 }
 public Evaluation details(String id){return read(id);}
 public Evaluation verify(String pin){var e=byPin(pin);available(e);return e;}
 public Lobby lobby(String id){return lobby(read(id));}
 private Lobby lobby(Evaluation e){
  var people=db.query("SELECT * FROM participants WHERE evaluation_id=? AND active=TRUE ORDER BY last_seen,id",(r,n)->new Participant(r.getString("id"),r.getString("nickname"),r.getObject("last_seen",OffsetDateTime.class).toInstant().isBefore(Instant.now().minusSeconds(30))?"DISCONNECTED":"CONNECTED"),e.id());
  String screen=e.state().equals("ENDED")?"ENDED":e.expiresAt()!=null&&!e.expiresAt().isAfter(Instant.now())?"EXPIRED":e.state().equals("STARTED")?"SOLVING":"WAITING";
  var ends=db.queryForObject("SELECT ends_at FROM evaluations WHERE id=?",OffsetDateTime.class,e.id());
  Instant endsAt=ends==null?null:ends.toInstant();
  return new Lobby(e.id(),e.title(),e.state(),screen,people.size(),people,endsAt,Instant.now());
 }
 public Joined join(String pin,String nickname,String token){
  var e=byPin(pin);available(e);
  if(token!=null&&!token.isBlank()) {
   var guest=new kr.lit.auth.ParticipantAccess(db).authenticateReconnect(e.id(),token);
   db.update("UPDATE participants SET active=TRUE,last_seen=CURRENT_TIMESTAMP WHERE id=? AND expelled_at IS NULL",guest.participantId());
   return new Joined(guest.participantId(),token,personalLobby(e,guest.participantId()));
  }
  if(!e.state().equals("WAITING")&&e.assessmentId()==null)throw fail(HttpStatus.CONFLICT,"ALREADY_STARTED","시작된 평가에는 새로 참여할 수 없습니다.");
  String name=nickname==null?"":nickname.strip();
  if(name.isBlank()||name.codePointCount(0,name.length())>20)throw fail(HttpStatus.BAD_REQUEST,"INVALID_NICKNAME","닉네임은 1~20자여야 합니다.");
  String id=UUID.randomUUID().toString();byte[] bytes=new byte[32];random.nextBytes(bytes);String secret=HexFormat.of().formatHex(bytes);
  try{db.update("INSERT INTO participants(id,evaluation_id,nickname,token,active,last_seen,token_hashed) VALUES (?,?,?,?,TRUE,CURRENT_TIMESTAMP,TRUE)",id,e.id(),name,kr.lit.auth.AuthService.hash(secret));}
  catch(org.springframework.dao.DuplicateKeyException ex){throw fail(HttpStatus.CONFLICT,"NICKNAME_TAKEN","사용 중인 닉네임입니다.");}
  return new Joined(id,secret,lobby(e));
 }
 public Lobby participant(String id,String token,boolean leave){
  var e=read(id);var guest=new kr.lit.auth.ParticipantAccess(db).authenticate(id,token);
  if(e.state().equals("STARTED")) {work.finishEvaluation(id,true);e=read(id);}
  db.update("UPDATE participants SET active=?,last_seen=CURRENT_TIMESTAMP WHERE id=?",!leave,guest.participantId());
  return personalLobby(e,guest.participantId());
 }
 private Lobby personalLobby(Evaluation e,String participantId){
  var lobby=lobby(e);
  boolean submitted=db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,participantId)>0;
  return submitted?new Lobby(lobby.evaluationId(),lobby.title(),lobby.state(),"SUBMITTED",lobby.participantCount(),lobby.participants(),lobby.endsAt(),lobby.serverNow()):lobby;
 }
 public Lobby start(String id){var e=read(id);ClassSnapshots.requireStandalone(db,id);available(e);
  if(!e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"ALREADY_STARTED","이미 시작된 평가입니다.");
  int count=db.queryForObject("SELECT COUNT(*) FROM participants WHERE evaluation_id=? AND active=TRUE AND last_seen>=?",Integer.class,id,OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30));
  if(count==0)throw fail(HttpStatus.CONFLICT,"NO_PARTICIPANTS","연결된 참가자가 없습니다.");
  int minutes=db.queryForObject("SELECT duration_minutes FROM evaluations WHERE id=?",Integer.class,id);
  var started=OffsetDateTime.now(ZoneOffset.UTC);var ends=started.plusMinutes(minutes);
  db.update("UPDATE evaluations SET state='STARTED',started_at=?,ends_at=?,expires_at=? WHERE id=?",started,ends,ends,id);return lobby(read(id));
 }
 public Lobby extend(String id,int additionalMinutes) {
  kr.lit.auth.AccountAccess.require(kr.lit.auth.AuthModels.Role.TEACHER);
  var e=read(id);
  if(additionalMinutes<1||additionalMinutes>600)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","연장은1~600분이어야 합니다.");
  var old=db.queryForObject("SELECT ends_at FROM evaluations WHERE id=?",OffsetDateTime.class,id);
  Instant effective=old==null?e.expiresAt():old.toInstant();
  if(!e.state().equals("STARTED")||effective==null||!effective.isAfter(Instant.now()))
   throw fail(HttpStatus.CONFLICT,"EVALUATION_NOT_RUNNING","현재 진행 중인 평가만 연장할 수 있습니다.");
  var deadline=OffsetDateTime.ofInstant(effective,ZoneOffset.UTC);
  db.update("UPDATE evaluations SET ends_at=?,expires_at=?,duration_minutes=duration_minutes+? WHERE id=?",deadline.plusMinutes(additionalMinutes),deadline.plusMinutes(additionalMinutes),additionalMinutes,id);
  return lobby(read(id));
 }
 void openClassLobby(String id,int minutes) {
  var e=read(id);
  if(e.assessmentId()==null||!e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"CLASS_LOBBY_LOCKED","시작 전 반만 대기실을 열 수 있습니다.");
  if(minutes<1||minutes>1440)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","대기실 유효 시간은1~1440분이어야 합니다.");
  // 같은 대기실 재진입·중복 클릭은 기존 PIN과 유효 시간을 유지합니다.
  if(e.pin()!=null&&e.expiresAt()!=null&&e.expiresAt().isAfter(Instant.now()))return;
  String pin=e.pin();
  for(int i=0;pin==null&&i<100;i++) {
   String candidate=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
   if(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE pin=?",Integer.class,candidate)==0)pin=candidate;
  }
  if(pin==null)throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PIN_UNAVAILABLE","PIN을 다시 생성해주세요.");
  var expires=OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(minutes);
  try {db.update("UPDATE evaluations SET pin=?,expires_at=? WHERE id=?",pin,expires,id);}
  catch(org.springframework.dao.DuplicateKeyException ex){throw fail(HttpStatus.CONFLICT,"PIN_COLLISION","PIN 생성에 실패했습니다. 대기실 열기를 다시 시도해주세요.");}
 }
 void activateClass(String id,int minutes) {
  var e=read(id);
  if(e.assessmentId()==null||!e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"ALREADY_STARTED","시작 전 반 회차가 아닙니다.");
  if(e.pin()==null)throw fail(HttpStatus.CONFLICT,"CLASS_LOBBY_REQUIRED","먼저 대기실을 열어 학생에게 PIN을 안내해주세요.");
  available(e);
  if(minutes<1||minutes>1440)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","풀이 시간은1~1440분이어야 합니다.");
  var now=OffsetDateTime.now(ZoneOffset.UTC);var end=now.plusMinutes(minutes);
  db.update("UPDATE evaluations SET state='STARTED',started_at=?,ends_at=?,expires_at=?,duration_minutes=? WHERE id=?",now,end,end,minutes,id);
 }
 public Lobby end(String id){var e=read(id);if(e.assessmentId()!=null&&e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"EVALUATION_NOT_STARTED","시작 전인 반은 종료할 수 없습니다.");work.finishEvaluation(id,false);return lobby(read(id));}
 public record Updated(String id,String title,String pin,String state,Instant expiresAt,int durationMinutes){}
 /** 시작 전(WAITING)에만 제목·풀이 시간·PIN 유효 시간을 바꿉니다. 문항·AI 정책은 PUT /questions를 사용합니다. */
 public Updated update(String id,String title,Integer durationMinutes,Integer expiresInMinutes){
  var e=read(id);ClassSnapshots.requireStandalone(db,id);
  if(!e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"EVALUATION_LOCKED","시작 전(WAITING) 평가만 수정할 수 있습니다.");
  if(title==null&&durationMinutes==null&&expiresInMinutes==null)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","변경할 값을 하나 이상 보내주세요.");
  if(expiresInMinutes==null&&!e.expiresAt().isAfter(Instant.now()))throw fail(HttpStatus.GONE,"PIN_EXPIRED","만료된 평가입니다. expiresInMinutes로 PIN 유효 시간을 연장할 수 있습니다.");
  if(title!=null){String t=title.strip();if(t.isEmpty()||t.length()>100)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","제목은 1~100자여야 합니다.");db.update("UPDATE evaluations SET title=? WHERE id=?",t,id);}
  if(durationMinutes!=null)db.update("UPDATE evaluations SET duration_minutes=? WHERE id=?",durationMinutes,id);
  if(expiresInMinutes!=null)db.update("UPDATE evaluations SET expires_at=? WHERE id=?",OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(expiresInMinutes),id);
  var u=read(id);
  return new Updated(u.id(),u.title(),u.pin(),u.state(),u.expiresAt(),db.queryForObject("SELECT duration_minutes FROM evaluations WHERE id=?",Integer.class,id));
 }
 /** 진행 중(STARTED, 마감 전)인 평가는 삭제할 수 없습니다. 참가·답안·대화·채점·리포트·알림 기록을 함께 삭제합니다. */
 public void delete(String id){
  var e=read(id);
  if(e.assessmentId()!=null)throw fail(HttpStatus.CONFLICT,"CLASS_CONFIGURATION_MANAGED","반 삭제는 수행평가 틀의 반 삭제 API를 사용해주세요.");
  deleteFromAssessment(id);
 }
 void deleteFromAssessment(String id){
  var e=read(id);
  if(e.state().equals("STARTED")&&e.expiresAt().isAfter(Instant.now()))throw fail(HttpStatus.CONFLICT,"EVALUATION_IN_PROGRESS","진행 중인 평가는 종료한 뒤 삭제해주세요.");
  for(String table:List.of("notifications","sentence_labels","purpose_labels","purpose_classifications","grading_score_changes","grading_scores","grading_results","grading_analyses","evaluation_rubrics","ai_disclosures",
    "usage_reports","teacher_review_changes","teacher_reviews","submissions","learning_events","ai_turns"))
   db.update("DELETE FROM "+table+" WHERE evaluation_id=?",id);
  db.update("DELETE FROM answers WHERE participant_id IN (SELECT id FROM participants WHERE evaluation_id=?)",id);
  db.update("DELETE FROM questions WHERE evaluation_id=?",id);
  db.update("DELETE FROM evaluation_settings WHERE evaluation_id=?",id);
  db.update("DELETE FROM participants WHERE evaluation_id=?",id);
  db.update("DELETE FROM evaluations WHERE id=?",id);
 }
}
