package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class SchemaUpgradeTest {
    @Test void legacyRowsSurviveAndAreNotClaimedByNewUsers() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:legacy_upgrade;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(source);
        db.execute("CREATE TABLE evaluations(id VARCHAR(36) PRIMARY KEY,title VARCHAR(100) NOT NULL,pin VARCHAR(6) NOT NULL UNIQUE,state VARCHAR(20) NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        db.execute("CREATE TABLE participants(id VARCHAR(36) PRIMARY KEY,evaluation_id VARCHAR(36) NOT NULL REFERENCES evaluations(id),nickname VARCHAR(20) NOT NULL,token VARCHAR(64) NOT NULL UNIQUE,active BOOLEAN NOT NULL,last_seen TIMESTAMP WITH TIME ZONE NOT NULL,UNIQUE(evaluation_id,nickname))");
        db.update("INSERT INTO evaluations VALUES ('legacy-e','기존 평가','000001','WAITING',CURRENT_TIMESTAMP)");
        db.update("INSERT INTO participants VALUES ('legacy-p','legacy-e','기존 학생','old-token',TRUE,CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE questions(id VARCHAR(36) PRIMARY KEY,evaluation_id VARCHAR(36) NOT NULL REFERENCES evaluations(id),position INT NOT NULL,title VARCHAR(200) NOT NULL,content VARCHAR(10000) NOT NULL,UNIQUE(evaluation_id,position))");
        db.update("INSERT INTO questions VALUES ('legacy-q','legacy-e',2,'기존 문항','보존할 내용')");
        var migration=new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        migration.execute(source);migration.execute(source);
        assertThat(db.queryForObject("SELECT title FROM evaluations WHERE id='legacy-e'",String.class)).isEqualTo("기존 평가");
        assertThat(db.queryForObject("SELECT teacher_id FROM evaluations WHERE id='legacy-e'",String.class)).isNull();
        assertThat(db.queryForObject("SELECT user_id FROM participants WHERE id='legacy-p'",String.class)).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT assessment_id FROM evaluations WHERE id='legacy-e'",String.class)).isNull();
        assertThat(db.queryForObject("SELECT pin FROM evaluations WHERE id='legacy-e'",String.class)).isEqualTo("000001");
        assertThat(db.queryForObject("SELECT anti_cheat_json FROM evaluations WHERE id='legacy-e'",String.class)).isEqualTo("{}");
        assertThat(db.queryForObject("SELECT content FROM questions WHERE id='legacy-q'",String.class)).isEqualTo("보존할 내용");
        assertThat(db.queryForObject("SELECT min_chars FROM questions WHERE id='legacy-q'",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT max_chars FROM questions WHERE id='legacy-q'",Integer.class)).isNull();
        db.update("INSERT INTO questions(id,evaluation_id,position,title,content) VALUES ('new-q','legacy-e',1,'기본 제한','본문')");
        assertThat(db.queryForObject("SELECT min_chars FROM questions WHERE id='new-q'",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT max_chars FROM questions WHERE id='new-q'",Integer.class)).isNull();
    }
}
