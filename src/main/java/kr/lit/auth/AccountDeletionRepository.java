package kr.lit.auth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;
@Repository
public class AccountDeletionRepository {
    private final JdbcTemplate db;
    public AccountDeletionRepository(JdbcTemplate db) {this.db=db;}
    public List<Map<String,Object>> lockAccount(String userId) {return db.queryForList("SELECT * FROM app_users WHERE id=? AND user_status='ACTIVE' FOR UPDATE",userId);}
    public boolean existsSession(String userId,String sessionId) {return db.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND access_expires_at>CURRENT_TIMESTAMP",Integer.class,sessionId,userId)>0;}
    public List<String> lockTemplates(String userId) {return db.queryForList("SELECT id FROM assessment_templates WHERE teacher_id=? ORDER BY id FOR UPDATE",String.class,userId);}
    public List<String> lockEvaluations(String userId) {return db.queryForList("SELECT id FROM evaluations WHERE (assessment_id IS NULL AND teacher_id=?) OR assessment_id IN (SELECT id FROM assessment_templates WHERE teacher_id=?) ORDER BY id FOR UPDATE",String.class,userId,userId);}
    public boolean existsRunningEvaluation(String evaluationId) {return db.queryForObject("SELECT COUNT(*) FROM evaluations WHERE id=? AND state='STARTED' AND COALESCE(ends_at,expires_at)>CURRENT_TIMESTAMP",Integer.class,evaluationId)>0;}
    public void deleteEvaluation(String evaluationId) {
        // 모든 참가자 종속 레코드를 지운 뒤 부모를 지웁니다. 테이블명은 고정 목록입니다.
        for(String table:List.of("notifications","sentence_labels","purpose_labels","purpose_classifications","rubric_labels","rubric_judgments","rubric_runs","judge_usage","grading_score_changes","grading_scores","grading_results","grading_analyses","evaluation_rubrics","ai_disclosures","usage_reports","teacher_review_changes","teacher_reviews","submissions","learning_events","ai_turns"))
            db.update("DELETE FROM "+table+" WHERE evaluation_id=?",evaluationId);
        db.update("DELETE FROM answers WHERE participant_id IN (SELECT id FROM participants WHERE evaluation_id=?) OR question_id IN (SELECT id FROM questions WHERE evaluation_id=?)",evaluationId,evaluationId);
        db.update("DELETE FROM questions WHERE evaluation_id=?",evaluationId);
        db.update("DELETE FROM evaluation_settings WHERE evaluation_id=?",evaluationId);
        db.update("DELETE FROM participants WHERE evaluation_id=?",evaluationId);
        db.update("DELETE FROM evaluations WHERE id=?",evaluationId);
    }
    public void deleteTemplate(String assessmentId) {
        db.update("DELETE FROM notifications WHERE assessment_id=?",assessmentId);
        db.update("DELETE FROM assessment_collaborators WHERE assessment_id=?",assessmentId);
        db.update("DELETE FROM assessment_templates WHERE id=?",assessmentId);
    }
    public void deleteAccountData(String userId,String email) {
        db.update("DELETE FROM assessment_collaborators WHERE teacher_id=?",userId);
        db.update("DELETE FROM notifications WHERE user_id=?",userId);
        db.update("DELETE FROM user_settings WHERE user_id=?",userId);
        db.update("DELETE FROM user_policy_consents WHERE user_id=?",userId);
        db.update("DELETE FROM auth_sessions WHERE user_id=?",userId);
        db.update("DELETE FROM password_reset_tokens WHERE user_id=?",userId);
        db.update("DELETE FROM signup_email_verifications WHERE email=?",email);
        // 이메일 변경 기능은 별도 브랜치여서 해당 테이블이 추가된 경우에만 정리합니다.
        if(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='EMAIL_CHANGE_VERIFICATIONS'",Integer.class)>0)
            db.update("DELETE FROM email_change_verifications WHERE user_id=?",userId);
        db.update("UPDATE participants SET user_id=NULL WHERE user_id=?",userId);
        db.update("UPDATE teacher_invites SET created_by=NULL WHERE created_by=?",userId);
        db.update("UPDATE teacher_invites SET used_by=NULL WHERE used_by=?",userId);
    }
    public void anonymizeAccount(String userId,String passwordHash) {
        String tombstone=UUID.randomUUID().toString();
        db.update("UPDATE app_users SET username=?,email=?,password_hash=?,display_name='탈퇴한 사용자',school='',user_status='DELETED',grade=NULL,class_number=NULL,student_number=NULL,failed_logins=0,locked_until=NULL,updated_at=? WHERE id=?",
            "deleted_"+tombstone.replace("-","").substring(0,22),"deleted-"+tombstone+"@example.invalid",passwordHash,OffsetDateTime.now(ZoneOffset.UTC),userId);
    }
}
