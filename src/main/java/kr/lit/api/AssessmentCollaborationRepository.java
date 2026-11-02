package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/** 공유 대상 계정 조회 및 공유 인원 집계. 권한과 오류 정책은 서비스에서 판단합니다. */
@Repository
public class AssessmentCollaborationRepository {
    private final JdbcTemplate db;
    public AssessmentCollaborationRepository(JdbcTemplate db) {this.db=db;}
    public record SharingUser(String id,String name,String email,String role,String status) {}
    public Optional<SharingUser> findUserByEmail(String email) {
        return db.query("SELECT id,display_name,email,user_role,user_status FROM app_users WHERE email=?",
                (r,n)->new SharingUser(r.getString("id"),r.getString("display_name"),r.getString("email"),r.getString("user_role"),r.getString("user_status")),email).stream().findFirst();
    }
    public int countCollaborators(String assessmentId) {
        return db.queryForObject("SELECT COUNT(*) FROM assessment_collaborators c JOIN app_users u ON u.id=c.teacher_id WHERE c.assessment_id=? AND u.user_role='TEACHER' AND u.user_status='ACTIVE'",Integer.class,assessmentId);
    }
}
