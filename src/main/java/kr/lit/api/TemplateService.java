package kr.lit.api;

import kr.lit.db.DatabaseUpsert;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import kr.lit.auth.AccountAccess;
import kr.lit.auth.AssessmentAccess;
import kr.lit.auth.AuthModels.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import static kr.lit.api.TemplateModels.*;
import static kr.lit.api.WorkModels.*;
import static kr.lit.api.AssessmentModels.*;

@Service
@Transactional
public class TemplateService {
    private final JdbcTemplate db;
    private final AssessmentCollaborationRepository collaborationRepository;
    private final ObjectMapper json;
    private final EvaluationService evaluations;
    private final String joinBase;
    private final NotificationService notifications;
    private final jakarta.validation.Validator validator;
    public TemplateService(JdbcTemplate db,ObjectMapper json,EvaluationService evaluations,@Value("${lit.join-base-url}") String joinBase,jakarta.validation.Validator validator,NotificationService notifications,AssessmentCollaborationRepository collaborationRepository) {
        this.db=db;this.collaborationRepository=collaborationRepository;this.json=json;this.evaluations=evaluations;this.joinBase=joinBase;this.validator=validator;this.notifications=notifications;
    }
    private record Stored(String id,Input input,int revision,Instant createdAt,Instant updatedAt,Owner owner,String role) {}
    private ApiError.Failure fail(HttpStatus status,String code,String message) {return new ApiError.Failure(status,code,message);}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private <T> T decode(String value,TypeReference<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode node(String value) {try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static Instant time(ResultSet r,String column) throws SQLException {var v=r.getObject(column,OffsetDateTime.class);return v==null?null:v.toInstant();}
    private Stored row(ResultSet r,int n) throws SQLException {
        var input=new Input(r.getString("title"),r.getString("guidelines"),AiPolicy.valueOf(r.getString("ai_policy")),
                decode(r.getString("criteria_json"),new TypeReference<List<Object>>(){}),node(r.getString("anti_cheat_json")),
                decode(r.getString("questions_json"),new TypeReference<List<QuestionInput>>(){}),r.getInt("duration_minutes"));
        return new Stored(r.getString("id"),input,r.getInt("revision"),time(r,"created_at"),time(r,"updated_at"),new Owner(r.getString("teacher_id"),r.getString("owner_name")),r.getString("teacher_id").equals(AccountAccess.require(Role.TEACHER).userId())?"OWNER":"EDITOR");
    }
    /** 모든 공통 수정/시작/삭제는 부모를 먼저 잠그고 회차를 잠급니다. */
    private Stored accessible(String id) {
        AssessmentAccess.requireTemplate(db,id,false);
        return db.queryForObject("SELECT t.*,u.display_name owner_name FROM assessment_templates t JOIN app_users u ON u.id=t.teacher_id WHERE t.id=?",this::row,id);
    }
    private Input normalize(Input input) {
        QuestionLimits.validate(input.questions());
        var criteria=CommonSettingsRules.criteria(json,validator,input.criteria());
        JsonNode anti=CommonSettingsRules.antiCheat(json,input.antiCheat());
        return new Input(input.title().strip(),Guidelines.clean(input.guidelines()==null?"":input.guidelines()),input.aiPolicy(),criteria,anti,
                input.questions().stream().sorted(Comparator.comparingInt(QuestionInput::position)).map(q->new QuestionInput(q.position(),q.title().strip(),q.content(),q.minChars(),q.maxChars())).toList(),input.durationMinutes()==null?60:input.durationMinutes());
    }
    public View create(Input input) {
        String teacher=AccountAccess.require(Role.TEACHER).userId();var v=normalize(input);String id=UUID.randomUUID().toString();
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        db.update("INSERT INTO assessment_templates(id,teacher_id,title,guidelines,ai_policy,criteria_json,anti_cheat_json,questions_json,duration_minutes,revision,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,1,?,?)",
                id,teacher,v.title(),v.guidelines(),v.aiPolicy().name(),encode(v.criteria()),encode(v.antiCheat()),encode(v.questions()),v.durationMinutes(),now,now);
        return detail(id);
    }
    public View detail(String id) {var s=accessible(id);return view(s);}
    private View view(Stored s) {
        var v=s.input();var classes=classViews(s.id());
        return new View(s.id(),v.title(),v.guidelines(),v.aiPolicy(),v.criteria(),v.antiCheat(),v.questions(),v.durationMinutes(),s.revision(),v.questions().size(),classes.size(),classes,s.createdAt(),s.updatedAt(),s.owner(),s.role(),collaborationRepository.countCollaborators(s.id()));
    }
    public Page list(int page,int size) {return list(page,size,"mine");}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(int page,int size,String scope) {
        String teacher=AccountAccess.require(Role.TEACHER).userId();
        if(page<0||size<1||size>100)throw fail(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","page는0 이상, size는1~100이어야 합니다.");
        if(!scope.equals("mine")&&!scope.equals("shared")&&!scope.equals("all"))throw fail(HttpStatus.BAD_REQUEST,"INVALID_SCOPE","scope는 mine, shared 또는 all이어야 합니다.");
        String membership="EXISTS (SELECT 1 FROM assessment_collaborators c WHERE c.assessment_id=t.id AND c.teacher_id=?)";
        String filter=switch(scope) {case "mine"->"t.teacher_id=?";case "shared"->"t.teacher_id<>? AND "+membership;default->"(t.teacher_id=? OR "+membership+")";};
        Object[] args=scope.equals("mine")?new Object[]{teacher}:new Object[]{teacher,teacher};
        long count=db.queryForObject("SELECT COUNT(*) FROM assessment_templates t WHERE "+filter,Long.class,args);
        var queryArgs=new ArrayList<Object>(List.of(args));queryArgs.add(size);queryArgs.add((long)page*size);
        var templates=db.query("SELECT t.*,u.display_name owner_name FROM assessment_templates t JOIN app_users u ON u.id=t.teacher_id WHERE "+filter+" ORDER BY t.updated_at DESC,t.id DESC LIMIT ? OFFSET ?",this::row,queryArgs.toArray());
        var summaries=templates.stream().map(s->{var classes=classViews(s.id());String text=org.jsoup.Jsoup.parseBodyFragment(s.input().guidelines()).text();
            String summary=text.codePointCount(0,text.length())>200?text.substring(0,text.offsetByCodePoints(0,200)):text;
            return new Summary(s.id(),s.input().title(),s.input().questions().size(),classes.size(),classes,s.revision(),s.updatedAt(),s.owner(),s.role(),s.input().aiPolicy(),summary,collaborationRepository.countCollaborators(s.id()));}).toList();
        return new Page(summaries,page,size,count,(count+size-1)/size,scope);
    }
    public View update(String id,Input input) {
        var previous=accessible(id);
        if(input.revision()!=null&&input.revision()!=previous.revision())
            throw fail(HttpStatus.CONFLICT,"ASSESSMENT_REVISION_CONFLICT","다른 교사가 수정했습니다. 최신 평가를 불러온 후 다시 저장해주세요.");
        var v=normalize(input);int revision=previous.revision()+1;
        db.update("UPDATE assessment_templates SET title=?,guidelines=?,ai_policy=?,criteria_json=?,anti_cheat_json=?,questions_json=?,duration_minutes=?,revision=?,updated_at=? WHERE id=?",
                v.title(),v.guidelines(),v.aiPolicy().name(),encode(v.criteria()),encode(v.antiCheat()),encode(v.questions()),v.durationMinutes(),revision,OffsetDateTime.now(ZoneOffset.UTC),id);
        var waiting=db.queryForList("SELECT id FROM evaluations WHERE assessment_id=? AND state='WAITING' ORDER BY id FOR UPDATE",String.class,id);
        for(String classId:waiting)snapshot(classId,v,revision);
        return detail(id);
    }
    private void snapshot(String classId,Input v,int revision) {
        // 시작 전에는 PIN과 참가자가 없으므로 문항 ID를 재발급해도 학생 작업을 잃지 않습니다.
        if(db.queryForObject("SELECT COUNT(*) FROM participants WHERE evaluation_id=?",Integer.class,classId)>0)
            throw fail(HttpStatus.CONFLICT,"CLASS_SNAPSHOT_LOCKED","참가 기록이 있는 회차는 공통 설정을 교체할 수 없습니다.");
        db.update("UPDATE evaluations SET title=?,evaluation_content=?,duration_minutes=?,criteria_json=?,criteria_kind=?,anti_cheat_json=?,template_revision=? WHERE id=?",
                v.title(),v.guidelines(),v.durationMinutes(),encode(v.criteria()),CommonSettingsRules.kind(v.criteria()),encode(v.antiCheat()),revision,classId);
        DatabaseUpsert.save(db,"evaluation_settings","evaluation_id,ai_policy","evaluation_id","?,?",classId,v.aiPolicy().name());
        db.update("DELETE FROM questions WHERE evaluation_id=?",classId);
        for(var q:v.questions())db.update("INSERT INTO questions(id,evaluation_id,position,title,content,min_chars,max_chars) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),classId,q.position(),q.title().strip(),q.content(),q.minChars()==null?0:q.minChars(),q.maxChars());
        db.update("DELETE FROM evaluation_rubrics WHERE evaluation_id=?",classId);
        CommonSettingsRules.saveRubric(db,json,classId,v.criteria());
    }

    public ClassView addClass(String id,String className) {
        var s=accessible(id);String name=className(className);
        String key=name.toLowerCase(Locale.ROOT);
        if(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE assessment_id=? AND class_name_key=?",Integer.class,id,key)>0)
            throw fail(HttpStatus.CONFLICT,"CLASS_NAME_TAKEN","같은 수행평가 안에서 반 이름은 중복될 수 없습니다.");
        String classId=UUID.randomUUID().toString();
        try {db.update("INSERT INTO evaluations(id,title,state,teacher_id,assessment_id,class_name,class_name_key,duration_minutes) VALUES (?,?,'WAITING',?,?,?,?,?)",
                classId,s.input().title(),s.owner().teacherId(),id,name,key,s.input().durationMinutes());}
        catch(org.springframework.dao.DuplicateKeyException ex){throw fail(HttpStatus.CONFLICT,"CLASS_NAME_TAKEN","이미 있는 반 이름입니다.");}
        snapshot(classId,s.input(),s.revision());return classView(id,classId);
    }
    private String className(String value) {
        String name=Normalizer.normalize(value.strip(),Normalizer.Form.NFKC);
        if(name.isBlank()||name.codePointCount(0,name.length())>30)throw fail(HttpStatus.BAD_REQUEST,"INVALID_CLASS_NAME","반 이름은1~30자여야 합니다.");
        return name;
    }
    public ClassView renameClass(String id,String classId,String value) {
        accessible(id);classView(id,classId);evaluations.details(classId);
        String name=className(value),key=name.toLowerCase(Locale.ROOT);
        if(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE assessment_id=? AND class_name_key=? AND id<>?",Integer.class,id,key,classId)>0)
            throw fail(HttpStatus.CONFLICT,"CLASS_NAME_TAKEN","같은 수행평가 안에서 반 이름은 중복될 수 없습니다.");
        try {db.update("UPDATE evaluations SET class_name=?,class_name_key=? WHERE id=? AND assessment_id=?",name,key,classId,id);}
        catch(org.springframework.dao.DuplicateKeyException ex){throw fail(HttpStatus.CONFLICT,"CLASS_NAME_TAKEN","이미 있는 반 이름입니다.");}
        return classView(id,classId);
    }
    public Collaborator addCollaborator(String id,String email,String role) {
        AssessmentAccess.requireTemplate(db,id,true);
        if(!"EDITOR".equals(role))throw fail(HttpStatus.BAD_REQUEST,"INVALID_COLLABORATOR_ROLE","공유 역할은 EDITOR만 지정할 수 있습니다.");
        return share(id,email);
    }
    public Collaborator share(String id,String email) {
        var access=AssessmentAccess.requireTemplate(db,id,true);
        String normalized=email.strip().toLowerCase(Locale.ROOT);
        var user=collaborationRepository.findUserByEmail(normalized)
                .orElseThrow(()->fail(HttpStatus.NOT_FOUND,"TEACHER_NOT_FOUND","해당 이메일의 계정이 없습니다."));
        if(!user.role().equals("TEACHER"))throw fail(HttpStatus.NOT_FOUND,"SHARE_TARGET_NOT_TEACHER","교사 계정에만 공유할 수 있습니다.");
        if(!user.status().equals("ACTIVE"))throw fail(HttpStatus.NOT_FOUND,"SHARE_TARGET_INACTIVE","사용 중인 교사 계정에만 공유할 수 있습니다.");
        String[] target={user.id(),user.name(),user.email()};
        if(target[0].equals(access.ownerId()))throw fail(HttpStatus.CONFLICT,"CANNOT_SHARE_WITH_SELF","본인에게는 공유할 수 없습니다.");
        if(db.queryForObject("SELECT COUNT(*) FROM assessment_collaborators WHERE assessment_id=? AND teacher_id=?",Integer.class,id,target[0])>0)
            throw fail(HttpStatus.CONFLICT,"COLLABORATOR_ALREADY_EXISTS","이미 공유한 교사입니다.");
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        try {db.update("INSERT INTO assessment_collaborators(assessment_id,teacher_id,shared_by,shared_at) VALUES (?,?,?,?)",id,target[0],access.ownerId(),now);}
        catch(org.springframework.dao.DuplicateKeyException ex){throw fail(HttpStatus.CONFLICT,"COLLABORATOR_ALREADY_EXISTS","이미 공유한 교사입니다.");}
        String title=db.queryForObject("SELECT title FROM assessment_templates WHERE id=?",String.class,id);
        notifications.assessmentShared(id,target[0],title);
        return new Collaborator(target[0],target[1],target[2],now.toInstant());
    }
    public List<Collaborator> collaborators(String id) {
        AssessmentAccess.requireTemplate(db,id,false);
        return db.query("SELECT c.teacher_id,u.display_name,u.email,c.shared_at FROM assessment_collaborators c JOIN app_users u ON u.id=c.teacher_id WHERE c.assessment_id=? ORDER BY c.shared_at,c.teacher_id",
                (r,n)->new Collaborator(r.getString("teacher_id"),r.getString("display_name"),r.getString("email"),time(r,"shared_at")),id);
    }
    public void unshare(String id,String teacherId) {
        var access=AssessmentAccess.requireTemplate(db,id,false);
        String actor=AccountAccess.require(Role.TEACHER).userId();
        if(!access.role().equals("OWNER")&&!actor.equals(teacherId))
            throw fail(HttpStatus.FORBIDDEN,"ASSESSMENT_OWNER_REQUIRED","편집자는 본인의 공유만 해제할 수 있습니다.");
        if(db.update("DELETE FROM assessment_collaborators WHERE assessment_id=? AND teacher_id=?",id,teacherId)==0)
            throw fail(HttpStatus.NOT_FOUND,"COLLABORATOR_NOT_FOUND","공유된 교사를 찾을 수 없습니다.");
        db.update("DELETE FROM notifications WHERE assessment_id=? AND user_id=?",id,teacherId);
    }
    public List<ClassView> classes(String id) {accessible(id);return classViews(id);}
    private List<ClassView> classViews(String id) {
        return db.query("SELECT e.*,(SELECT COUNT(*) FROM participants p WHERE p.evaluation_id=e.id) participant_count,(SELECT COUNT(*) FROM submissions s WHERE s.evaluation_id=e.id) submitted_count FROM evaluations e WHERE assessment_id=? ORDER BY class_name_key,id",
                (r,n)->{
                    String classId=r.getString("id"),pin=r.getString("pin");
                    return new ClassView(classId,classId,id,r.getString("class_name"),r.getString("state").equals("WAITING")?"READY":r.getString("state"),r.getString("state"),pin,pin==null?null:joinBase+"?pin="+pin,
                            pin==null?null:"/api/teacher/evaluations/"+classId+"/qr",time(r,"expires_at"),time(r,"ends_at"),r.getInt("participant_count"),r.getInt("submitted_count"),r.getInt("template_revision"));
                },id);
    }
    private ClassView classView(String id,String classId) {
        return classViews(id).stream().filter(c->c.classId().equals(classId)).findFirst()
                .orElseThrow(()->fail(HttpStatus.NOT_FOUND,"CLASS_NOT_FOUND","해당 수행평가의 반이 아닙니다."));
    }
    public ClassView openLobby(String id,String classId,int minutes) {
        accessible(id);classView(id,classId);
        evaluations.openClassLobby(classId,minutes);return classView(id,classId);
    }
    public ClassView start(String id,String classId,int minutes) {
        accessible(id);var c=classView(id,classId);
        if(!c.evaluationState().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"ALREADY_STARTED","이미 시작했거나 종료된 반입니다.");
        evaluations.activateClass(classId,minutes);return classView(id,classId);
    }
    public ClassView end(String id,String classId) {
        accessible(id);var c=classView(id,classId);
        if(c.evaluationState().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"EVALUATION_NOT_STARTED","시작 전인 반은 종료할 수 없습니다.");
        evaluations.end(classId);return classView(id,classId);
    }
    public void deleteClass(String id,String classId) {
        accessible(id);var c=classView(id,classId);
        if(!c.evaluationState().equals("WAITING"))throw fail(HttpStatus.CONFLICT,"CLASS_DELETE_REQUIRES_WAITING","시작 전인 반만 삭제할 수 있습니다.");
        evaluations.deleteFromAssessment(classId);
    }
    public void delete(String id) {
        AssessmentAccess.requireTemplate(db,id,true);
        var ids=db.queryForList("SELECT id FROM evaluations WHERE assessment_id=? ORDER BY id FOR UPDATE",String.class,id);
        if(db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE assessment_id=? AND state='STARTED'",Integer.class,id)>0)
            throw fail(HttpStatus.CONFLICT,"ASSESSMENT_IN_PROGRESS","진행 중인 반을 모두 종료한 뒤 틀을 삭제해주세요.");
        for(String classId:ids)evaluations.deleteFromAssessment(classId);
        db.update("DELETE FROM notifications WHERE assessment_id=?",id);
        db.update("DELETE FROM assessment_collaborators WHERE assessment_id=?",id);
        db.update("DELETE FROM assessment_templates WHERE id=?",id);
    }
}
