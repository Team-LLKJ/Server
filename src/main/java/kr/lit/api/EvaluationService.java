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
 public record Evaluation(String id,String title,String pin,String state,Instant expiresAt){}
 public record EvaluationPage(List<Evaluation> items,int page,int size,long totalElements,long totalPages){}
 public record Participant(String id,String nickname,String presence){}
 public record Lobby(String evaluationId,String title,String state,String screen,int participantCount,List<Participant> participants,Instant endsAt,Instant serverNow){}
 public record Created(String id,String title,String pin,String state,Instant expiresAt,String joinUrl,String qrUrl){}
 public record Joined(String participantId,String participantToken,Lobby lobby){}
 private Failure fail(HttpStatus status,String code,String message){return new Failure(status,code,message);}
 private Evaluation read(String id){
  var rows=db.query("SELECT * FROM evaluations WHERE id=? FOR UPDATE",(r,n)->new Evaluation(r.getString("id"),r.getString("title"),r.getString("pin"),r.getString("state"),r.getObject("expires_at",OffsetDateTime.class).toInstant()),id);
  if(rows.isEmpty())throw fail(HttpStatus.NOT_FOUND,"EVALUATION_NOT_FOUND","평가를 찾을 수 없습니다.");
  kr.lit.auth.AccountAccess.checkTeacherOwner(db.queryForObject("SELECT teacher_id FROM evaluations WHERE id=?",String.class,id));
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
  if(!e.expiresAt().isAfter(Instant.now()))throw fail(HttpStatus.GONE,"PIN_EXPIRED","만료된 평가입니다.");
 }
 public Created create(String title,int minutes){return create(title,minutes,"");}
 public Created create(String title,int minutes,String evaluationContent){return create(title,minutes,evaluationContent,null);}
 public Created create(String title,int minutes,String evaluationContent,Integer durationMinutes){
  String teacherId=kr.lit.auth.AccountAccess.require(kr.lit.auth.AuthModels.Role.TEACHER).userId();
  // The database unique constraint also protects concurrent PIN creation.
  String pin=null;
  for(int i=0;i<100;i++) {String candidate=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
   if(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE pin=?",Integer.class,candidate)==0){pin=candidate;break;}}
  if(pin==null)throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PIN_UNAVAILABLE","PIN을 다시 생성해주세요.");
  String id=UUID.randomUUID().toString(); Instant expiry=Instant.now().plusSeconds(minutes*60L);
  try {db.update("INSERT INTO evaluations(id,title,pin,state,expires_at,teacher_id,evaluation_content,duration_minutes) VALUES (?,?,?,'WAITING',?,?,?,?)",id,title.strip(),pin,OffsetDateTime.ofInstant(expiry,ZoneOffset.UTC),teacherId,evaluationContent==null?"":Guidelines.clean(evaluationContent),durationMinutes==null?minutes:durationMinutes);}
  catch(org.springframework.dao.DuplicateKeyException e){throw fail(HttpStatus.CONFLICT,"PIN_COLLISION","PIN 생성에 실패했습니다. 다시 시도해주세요.");}
  return new Created(id,title.strip(),pin,"WAITING",expiry,baseUrl+"?pin="+pin,"/api/teacher/evaluations/"+id+"/qr");
 }
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public EvaluationPage mine(int page,int size){
  String teacherId=kr.lit.auth.AccountAccess.require(kr.lit.auth.AuthModels.Role.TEACHER).userId();
  if(page<0||size<1||size>100)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","page는 0 이상, size는 1~100이어야 합니다.");
  long total=db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE teacher_id=?",Long.class,teacherId);
  // Expiry is the existing persisted timestamp; do not present it as creation time.
  var items=db.query("SELECT id,title,pin,state,expires_at FROM evaluations WHERE teacher_id=? ORDER BY expires_at DESC,id DESC LIMIT ? OFFSET ?",
   (r,n)->new Evaluation(r.getString("id"),r.getString("title"),r.getString("pin"),r.getString("state"),r.getObject("expires_at",OffsetDateTime.class).toInstant()),teacherId,size,(long)page*size);
  return new EvaluationPage(items,page,size,total,(total+size-1)/size);
 }
 public Evaluation details(String id){return read(id);}
 public Evaluation verify(String pin){var e=byPin(pin);available(e);return e;}
 public Lobby lobby(String id){return lobby(read(id));}
 private Lobby lobby(Evaluation e){
  var people=db.query("SELECT * FROM participants WHERE evaluation_id=? AND active=TRUE ORDER BY last_seen,id",(r,n)->new Participant(r.getString("id"),r.getString("nickname"),r.getObject("last_seen",OffsetDateTime.class).toInstant().isBefore(Instant.now().minusSeconds(30))?"DISCONNECTED":"CONNECTED"),e.id());
  String screen=e.state().equals("ENDED")?"ENDED":!e.expiresAt().isAfter(Instant.now())?"EXPIRED":e.state().equals("STARTED")?"SOLVING":"WAITING";
  var ends=db.queryForObject("SELECT ends_at FROM evaluations WHERE id=?",OffsetDateTime.class,e.id());
  Instant endsAt=ends==null?null:ends.toInstant();
  return new Lobby(e.id(),e.title(),e.state(),screen,people.size(),people,endsAt,Instant.now());
 }
 public Joined join(String pin,String nickname,String token){
  var e=byPin(pin);available(e);
  if(token!=null&&!token.isBlank()) {
   var ids=db.queryForList("SELECT id FROM participants WHERE evaluation_id=? AND ((token_hashed=TRUE AND token=?) OR (token_hashed=FALSE AND token=?))",String.class,e.id(),kr.lit.auth.AuthService.hash(token),token);
   if(ids.isEmpty()||!token.matches("[a-f0-9]{64}"))throw fail(HttpStatus.UNAUTHORIZED,"INVALID_PARTICIPANT_TOKEN","재접속 토큰이 올바르지 않습니다.");
   db.update("UPDATE participants SET active=TRUE,last_seen=CURRENT_TIMESTAMP WHERE id=?",ids.get(0));
   var guest=new kr.lit.auth.ParticipantAccess(db).authenticate(e.id(),token);
   return new Joined(guest.participantId(),token,personalLobby(e,guest.participantId()));
  }
  if(!e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"ALREADY_STARTED","시작된 평가에는 새로 참여할 수 없습니다.");
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
 public Lobby start(String id){var e=read(id);available(e);
  if(!e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"ALREADY_STARTED","이미 시작된 평가입니다.");
  int count=db.queryForObject("SELECT COUNT(*) FROM participants WHERE evaluation_id=? AND active=TRUE AND last_seen>=?",Integer.class,id,OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30));
  if(count==0)throw fail(HttpStatus.CONFLICT,"NO_PARTICIPANTS","연결된 참가자가 없습니다.");
  int minutes=db.queryForObject("SELECT duration_minutes FROM evaluations WHERE id=?",Integer.class,id);
  var started=OffsetDateTime.now(ZoneOffset.UTC);var ends=started.plusMinutes(minutes);
  db.update("UPDATE evaluations SET state='STARTED',started_at=?,ends_at=?,expires_at=? WHERE id=?",started,ends,ends,id);return lobby(read(id));
 }
 public Lobby end(String id){read(id);work.finishEvaluation(id,false);return lobby(read(id));}
}
