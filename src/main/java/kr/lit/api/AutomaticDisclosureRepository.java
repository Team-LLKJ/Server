package kr.lit.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class AutomaticDisclosureRepository {
    private final JdbcTemplate db;
    public AutomaticDisclosureRepository(JdbcTemplate db){this.db=db;}
    public void lockEvaluation(String id){db.queryForList("SELECT id FROM evaluations WHERE id=? FOR UPDATE",String.class,id);}
    public List<String> getDisclosure(String pid){return db.queryForList("SELECT disclosure_json FROM system_ai_disclosures WHERE participant_id=?",String.class,pid);}
    public List<String> getSubmission(String pid){return db.queryForList("SELECT snapshot FROM submissions WHERE participant_id=?",String.class,pid);}
    public void saveDisclosure(String id,String pid,String value){db.update("INSERT INTO system_ai_disclosures(participant_id,evaluation_id,disclosure_json) VALUES (?,?,?)",pid,id,value);}
}
