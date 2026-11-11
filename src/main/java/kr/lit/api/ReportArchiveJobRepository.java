package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.*;

@Repository
public class ReportArchiveJobRepository {
    private final JdbcTemplate db;
    public ReportArchiveJobRepository(JdbcTemplate db){this.db=db;}
    public record Job(String id,String userId,String scopeId,boolean assessment,String classId,String format,boolean raw,String status,String path,String errorCode,Instant createdAt,Instant completedAt) {}
    private List<Job> query(String sql,Object... args){return db.query(sql,(r,n)->new Job(r.getString("id"),r.getString("user_id"),r.getString("scope_id"),r.getBoolean("assessment"),r.getString("class_id"),r.getString("format"),r.getBoolean("include_conversations"),r.getString("status"),r.getString("file_path"),r.getString("error_code"),r.getObject("created_at",OffsetDateTime.class).toInstant(),r.getObject("completed_at",OffsetDateTime.class)==null?null:r.getObject("completed_at",OffsetDateTime.class).toInstant()),args);}
    @Transactional public String createJob(String user,String scope,boolean assessment,String classId,String format,boolean raw){
        db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",user);
        if(db.queryForObject("SELECT COUNT(*) FROM report_archive_jobs WHERE user_id=? AND status IN ('PENDING','RUNNING')",Integer.class,user)>=3)throw new ApiError.Failure(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"ARCHIVE_JOB_LIMIT","진행 중인 ZIP 작업이 너무 많습니다.");
        String id=UUID.randomUUID().toString();db.update("INSERT INTO report_archive_jobs(id,user_id,scope_id,assessment,class_id,format,include_conversations,status,created_at) VALUES (?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP)",id,user,scope,assessment,classId,format,raw);return id;
    }
    public Optional<Job> getJob(String id){return query("SELECT * FROM report_archive_jobs WHERE id=?",id).stream().findFirst();}
    public List<Job> getPending(){return query("SELECT * FROM report_archive_jobs WHERE status='PENDING' ORDER BY created_at LIMIT 2");}
    public boolean claimJob(String id){return db.update("UPDATE report_archive_jobs SET status='RUNNING',started_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",id)==1;}
    public boolean isActive(String user){return db.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=? AND user_role='TEACHER' AND user_status='ACTIVE'",Integer.class,user)>0;}
    public void completeJob(String id,String path){db.update("UPDATE report_archive_jobs SET status='DONE',file_path=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",path,id);}
    public void failJob(String id,String code){db.update("UPDATE report_archive_jobs SET status='FAILED',error_code=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",code,id);}
    public List<Job> getExpired(){return query("SELECT * FROM report_archive_jobs WHERE status='DONE' AND completed_at<(CURRENT_TIMESTAMP - INTERVAL '1' HOUR)");}
    public void expireJob(String id){db.update("UPDATE report_archive_jobs SET status='EXPIRED',file_path=NULL WHERE id=?",id);}
    public void recoverJobs(){db.update("UPDATE report_archive_jobs SET status='FAILED',error_code='WORKER_INTERRUPTED',completed_at=CURRENT_TIMESTAMP WHERE status='RUNNING' AND started_at<(CURRENT_TIMESTAMP - INTERVAL '15' MINUTE)");}
}
