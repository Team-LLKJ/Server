package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ReportJobs {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ReportJobs.class);
 private final JdbcTemplate db;private final ReportService reports;
 public ReportJobs(JdbcTemplate db,ReportService reports){this.db=db;this.reports=reports;}
 @Scheduled(fixedDelay=30000,initialDelay=30000)
 public void generateSubmittedReports(){
  var pending=db.query("SELECT s.evaluation_id,s.participant_id FROM submissions s LEFT JOIN teacher_reviews t ON t.participant_id=s.participant_id WHERE NOT EXISTS (SELECT 1 FROM usage_reports u WHERE u.participant_id=s.participant_id) OR t.updated_at>(SELECT MAX(u.created_at) FROM usage_reports u WHERE u.participant_id=s.participant_id) ORDER BY s.submitted_at LIMIT 100",
    (r,n)->Map.entry(r.getString("evaluation_id"),r.getString("participant_id")));
  for(var target:pending){
   try{reports.generateForJob(target.getKey(),target.getValue());}
   catch(RuntimeException e){log.warn("Report generation failed for participant {}, type={}",target.getValue(),e.getClass().getSimpleName());}
  }
 }
}
