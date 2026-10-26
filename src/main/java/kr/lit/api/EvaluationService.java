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
 private final SecureRandom random=new SecureRandom();
 public EvaluationService(JdbcTemplate db,@Value("${lit.join-base-url}") String baseUrl){this.db=db;this.baseUrl=baseUrl;}
 public record Evaluation(String id,String title,String pin,String state,Instant expiresAt){}
 public record Participant(String id,String nickname,String presence){}
 public record Lobby(String evaluationId,String title,String state,String screen,int participantCount,List<Participant> participants){}
 public record Created(String id,String title,String pin,String state,Instant expiresAt,String joinUrl,String qrUrl){}
 public record Joined(String participantId,Lobby lobby){}
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
 public Created create(String title,int minutes){
  String teacherId=kr.lit.auth.AccountAccess.require(kr.lit.auth.AuthModels.Role.TEACHER).userId();
  // The database unique constraint also protects concurrent PIN creation.
  String pin=null;
  for(int i=0;i<100;i++) {String candidate=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
   if(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE pin=?",Integer.class,candidate)==0){pin=candidate;break;}}
  if(pin==null)throw fail(HttpStatus.SERVICE_UNAVAILABLE,"PIN_UNAVAILABLE","PIN을 다시 생성해주세요.");
  String id=UUID.randomUUID().toString(); Instant expiry=Instant.now().plusSeconds(minutes*60L);
  try {db.update("INSERT INTO evaluations(id,title,pin,state,expires_at,teacher_id) VALUES (?,?,?,'WAITING',?,?)",id,title.strip(),pin,OffsetDateTime.ofInstant(expiry,ZoneOffset.UTC),teacherId);}
  catch(org.springframework.dao.DuplicateKeyException e){throw fail(HttpStatus.CONFLICT,"PIN_COLLISION","PIN 생성에 실패했습니다. 다시 시도해주세요.");}
  return new Created(id,title.strip(),pin,"WAITING",expiry,baseUrl+"?pin="+pin,"/api/teacher/evaluations/"+id+"/qr");
 }
 public Evaluation details(String id){return read(id);}
 public Evaluation verify(String pin){var e=byPin(pin);available(e);return e;}
 public Lobby lobby(String id){return lobby(read(id));}
 private Lobby lobby(Evaluation e){
  var people=db.query("SELECT * FROM participants WHERE evaluation_id=? AND active=TRUE ORDER BY last_seen,id",(r,n)->new Participant(r.getString("id"),r.getString("nickname"),r.getObject("last_seen",OffsetDateTime.class).toInstant().isBefore(Instant.now().minusSeconds(30))?"DISCONNECTED":"CONNECTED"),e.id());
  String screen=e.state().equals("ENDED")?"ENDED":!e.expiresAt().isAfter(Instant.now())?"EXPIRED":e.state().equals("STARTED")?"SOLVING":"WAITING";
  return new Lobby(e.id(),e.title(),e.state(),screen,people.size(),people);
 }
 public Joined join(String pin,String nickname,String studentId){
  kr.lit.auth.AccountAccess.student(studentId);
  var e=byPin(pin);available(e);
  var existing=db.queryForList("SELECT id FROM participants WHERE evaluation_id=? AND user_id=?",String.class,e.id(),studentId);
  if(!existing.isEmpty()) {
   db.update("UPDATE participants SET active=TRUE,last_seen=CURRENT_TIMESTAMP WHERE id=?",existing.get(0));
   return new Joined(existing.get(0),personalLobby(e,studentId));
  }
  if(!e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"ALREADY_STARTED","시작된 평가에는 새로 참여할 수 없습니다.");
  String name=nickname==null?"":nickname.strip();
  if(name.isBlank()||name.codePointCount(0,name.length())>20)throw fail(HttpStatus.BAD_REQUEST,"INVALID_NICKNAME","닉네임은 1~20자여야 합니다.");
  String id=UUID.randomUUID().toString();String secret=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
  try{db.update("INSERT INTO participants(id,evaluation_id,nickname,token,active,last_seen,user_id) VALUES (?,?,?,?,TRUE,CURRENT_TIMESTAMP,?)",id,e.id(),name,secret,studentId);}
  catch(org.springframework.dao.DuplicateKeyException ex){throw fail(HttpStatus.CONFLICT,"NICKNAME_TAKEN","사용 중인 닉네임입니다.");}
  return new Joined(id,lobby(e));
 }
 public Lobby participant(String id,String studentId,boolean leave){
  var e=read(id);
  kr.lit.auth.AccountAccess.student(studentId);
  int count=db.update("UPDATE participants SET active=?,last_seen=CURRENT_TIMESTAMP WHERE evaluation_id=? AND user_id=? AND active=TRUE",!leave,id,studentId);
  if(count==0)throw fail(HttpStatus.UNAUTHORIZED,"PARTICIPATION_REQUIRED","평가에 참여한 계정만 이용할 수 있습니다.");
  return personalLobby(e,studentId);
 }
 private Lobby personalLobby(Evaluation e,String studentId){
  var lobby=lobby(e);
  boolean submitted=db.queryForObject("SELECT COUNT(*) FROM submissions s JOIN participants p ON s.participant_id=p.id WHERE p.evaluation_id=? AND p.user_id=?",Integer.class,e.id(),studentId)>0;
  return submitted?new Lobby(lobby.evaluationId(),lobby.title(),lobby.state(),"SUBMITTED",lobby.participantCount(),lobby.participants()):lobby;
 }
 public Lobby start(String id){var e=read(id);available(e);
  if(!e.state().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"ALREADY_STARTED","이미 시작된 평가입니다.");
  int count=db.queryForObject("SELECT COUNT(*) FROM participants WHERE evaluation_id=? AND active=TRUE AND last_seen>=?",Integer.class,id,OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30));
  if(count==0)throw fail(HttpStatus.CONFLICT,"NO_PARTICIPANTS","연결된 참가자가 없습니다.");
  db.update("UPDATE evaluations SET state='STARTED' WHERE id=?",id);return lobby(read(id));
 }
 public Lobby end(String id){read(id);db.update("UPDATE evaluations SET state='ENDED' WHERE id=?",id);return lobby(read(id));}
}
