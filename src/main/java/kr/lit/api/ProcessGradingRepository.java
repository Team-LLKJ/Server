package kr.lit.api;

import kr.lit.db.DatabaseUpsert;
import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import kr.lit.auth.AuthService;
import java.time.*;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;

@Repository
public class ProcessGradingRepository {
    private final JdbcTemplate db;private final ObjectMapper json;private final PurposeClassifier purposes;
    public ProcessGradingRepository(JdbcTemplate db,ObjectMapper json,PurposeClassifier purposes){this.db=db;this.json=json;this.purposes=purposes;}
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Cannot serialize process grading",ex);}}
    public <T> T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception ex){throw new IllegalStateException("Invalid stored process grading",ex);}}
    public Optional<Policy> getPolicy(String id){return db.queryForList("SELECT policy_json FROM process_grading_policies WHERE evaluation_id=?",String.class,id).stream().findFirst().map(s->decode(s,Policy.class));}
    public List<String> getQuestionIds(String id){return db.queryForList("SELECT id FROM questions WHERE evaluation_id=? ORDER BY position,id",String.class,id);}
    public String getEvaluationState(String id){return db.queryForObject("SELECT state FROM evaluations WHERE id=? FOR UPDATE",String.class,id);}
    public void savePolicy(String id,Policy policy){DatabaseUpsert.save(db,"process_grading_policies","evaluation_id,policy_json","evaluation_id","?,?",id,encode(policy));}
    public String queueSubmission(String id,String pid) {
        // 호출자는 평가 행을 잠근 제출 트랜잭션입니다. 제출 시점의 모든 입력을 별도 보존합니다.
        var rows=db.queryForList("SELECT s.snapshot,s.review_token,e.title,e.evaluation_content FROM submissions s JOIN evaluations e ON e.id=s.evaluation_id WHERE s.evaluation_id=? AND s.participant_id=?",id,pid);
        if(rows.isEmpty())throw new IllegalArgumentException("SUBMISSION_REQUIRED");
        var row=rows.get(0);JsonNode snapshot;
        try{snapshot=json.readTree((String)row.get("SNAPSHOT"));}catch(Exception ex){throw new IllegalStateException(ex);}
        snapshot=purposes.annotate(pid,snapshot);
        var sources=new ArrayList<Source>();var ids=new ArrayList<String>();var limitations=new ArrayList<String>();
        for(var a:snapshot.path("answers")) {
            String q=a.path("question").path("id").asText();ids.add(q);
            sources.add(new Source("task:"+q,q,"TASK",a.path("question").path("title").asText()+"\n"+plain(a.path("question").path("content").asText())));
            var paragraphs=AssessmentAnalyzer.paragraphs(a.path("answer").path("content").asText());
            int offset=0;for(int i=0;i<paragraphs.size();i++){
                sources.add(new Source("answer:"+q+":"+(i+1),q,"FINAL_ANSWER",paragraphs.get(i),a.path("answer").path("updatedAt").asText(null),a.path("answer").path("version").asInt(),null,null,offset));offset+=paragraphs.get(i).length()+1;
            }
        }
        for(var t:snapshot.path("conversations")) {
            if(!"COMPLETED".equals(t.path("state").asText()))limitations.add("대화 "+t.path("id").asText()+" 상태="+t.path("state").asText()+": 완료된 AI 응답이 없음");
            String q=t.path("questionId").asText(),key="turn:"+t.path("id").asText();
            sources.add(new Source(key+":tool",q,"AI_TOOL_METADATA","provider="+t.path("provider").asText("미기록")+"; model="+t.path("model").asText("미기록")));
            sources.add(new Source(key+":question",q,"STUDENT_QUESTION",t.path("questionText").asText(),t.path("createdAt").asText(null),t.path("answerVersion").asInt(),t.path("id").asLong(),"USER",0));
            if("COMPLETED".equals(t.path("state").asText()))sources.add(new Source(key+":answer",q,"AI_RESPONSE",t.path("answerText").asText(),t.path("completedAt").asText(null),t.path("answerVersion").asInt(),t.path("id").asLong(),"AI",0));
        }
        for(var e:snapshot.path("learningEvents"))if(e.path("type").asText().equals("ANSWER_SAVED")) {
            String q=e.path("questionId").asText(),key="event:"+e.path("id").asText();
            sources.add(new Source(key+":previous",q,"ANSWER_CHANGE",plain(e.path("details").path("previousContent").asText()),e.path("createdAt").asText(null),e.path("details").path("previousVersion").asInt()));
            sources.add(new Source(key+":current",q,"ANSWER_CHANGE",plain(e.path("details").path("content").asText()),e.path("createdAt").asText(null),e.path("details").path("version").asInt()));
        }
        for(var e:snapshot.path("learningEvents"))if(WorkModels.ClientEventType.warningNames().contains(e.path("type").asText()))
            sources.add(new Source("warning:"+e.path("id").asText(),e.path("questionId").isNull()?GLOBAL:e.path("questionId").asText(),"WARNING",e.path("type").asText()+" (브라우저 신고·참고용)",e.path("createdAt").asText(null),null));
        if(snapshot.hasNonNull("aiDisclosure")) {
            var disclosure=snapshot.path("aiDisclosure");if(!"SYSTEM_GENERATED".equals(disclosure.path("source").asText()))for(String field:List.of("tools","usedParts","reason"))sources.add(new Source("disclosure:"+switch(field){case "tools"->"TOOLS";case "usedParts"->"USED_PARTS";default->"REASON";},GLOBAL,"DISCLOSURE",disclosure.path(field).asText()));
        }else {
        var disclosures=db.queryForList("SELECT tools,used_parts,reason FROM ai_disclosures WHERE participant_id=?",pid);
        if(!disclosures.isEmpty())for(String field:List.of("TOOLS","USED_PARTS","REASON"))sources.add(new Source("disclosure:"+field,GLOBAL,"DISCLOSURE",Objects.toString(disclosures.get(0).get(field),"")));
            limitations.add("이전 제출본에는 활용 설명 스냅샷이 없어 기존 저장 자료를 사용합니다.");
        }
        limitations.add("외부 AI·저장되지 않은 입력·실제 외부 자료 열람은 자동 관측할 수 없습니다.");
        limitations.add("최종 제출본과 저장 이력에 존재하는 문장만 근거로 사용합니다.");
        limitations.add("별도 참고 자료의 원문·조사 방법은 확보하지 못했습니다.");
        var paths=new InputPathAnalyzer().analyzePaths(snapshot);
        if(paths.status().equals("PARTIAL"))limitations.add("일부 답안 입력 경로를 확인할 편집 이력이 없습니다.");
        if(sources.stream().noneMatch(s->s.kind().equals("DISCLOSURE")&&!s.text().isBlank()))limitations.add("학생이 작성한 별도 활용 설명이 없습니다. 자동 생성 설명은 학생 설명과 구분합니다.");
        String snapshotAiPolicy=snapshot.path("aiPolicy").asText();
        var policy=getPolicy(id).map(old->new Policy(old.version(),RUBRIC_VERSION,old.taskGoal(),old.allowedRules(),old.noAiPolicy(),old.questionWeights(),old.excludedCriteria())).orElseGet(()->getDefaultPolicy(ids,(String)row.get("TITLE"),(String)row.get("EVALUATION_CONTENT"),snapshotAiPolicy));
        var input=new Input(id,pid,(String)row.get("REVIEW_TOKEN"),(String)row.get("TITLE"),snapshot.path("aiPolicy").asText(),policy,ids,List.copyOf(sources),
                List.copyOf(limitations),new AnalysisScope(List.copyOf(ids),java.util.stream.StreamSupport.stream(snapshot.path("answers").spliterator(),false).map(a->new AnswerVersion(a.path("question").path("id").asText(),a.path("answer").path("version").asInt())).toList(),java.util.stream.StreamSupport.stream(snapshot.path("conversations").spliterator(),false).map(t->t.path("id").asLong()).toList(),List.copyOf(limitations)),paths,new PurposeUsageAnalyzer().analyzeUsage(snapshot));
        return insertJob(input);
    }
    private Policy getDefaultPolicy(List<String> questionIds,String title,String guidelines,String aiPolicy) {
        if (questionIds.isEmpty()) return null;
        String goal=guidelines==null?"":plain(guidelines).strip();
        String rules=switch (aiPolicy) {
            case "DISABLED" -> "AI 질문을 사용할 수 없습니다.";
            case "HINT_ONLY" -> "개념 설명과 풀이 힌트를 요청할 수 있습니다.";
            default -> "평가 안내에 따라 AI를 사용하고 제출 답안에 활용한 내용을 검토하세요.";
        };
        int units=1_000_000;
        int base=units/questionIds.size();
        int remainder=units%questionIds.size();
        var weights=new ArrayList<QuestionWeight>();
        for (int i=0;i<questionIds.size();i++) {
            weights.add(new QuestionWeight(questionIds.get(i),java.math.BigDecimal.valueOf(base+(i<remainder?1:0),4)));
        }
        return new Policy(0,RUBRIC_VERSION,goal.isBlank()?title:goal,rules,NoAiPolicy.HOLD,List.copyOf(weights));
    }
    private String plain(String text){return Jsoup.parseBodyFragment(text).wholeText();}
    public String insertJob(Input input) {
        String data=encode(input),hash=AuthService.hash(data),job=UUID.randomUUID().toString();
        int version=db.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM process_grading_jobs WHERE participant_id=?",Integer.class,input.participantId());
        // 다시 제출하면 아직 시작하지 않은 이전 작업은 채점하지 않습니다(마지막 제출본만 채점·확정).
        db.update("UPDATE process_grading_jobs SET status='SUPERSEDED',error_code='NEWER_SUBMISSION',completed_at=CURRENT_TIMESTAMP WHERE participant_id=? AND status='PENDING'",input.participantId());
        db.update("INSERT INTO process_grading_jobs(id,evaluation_id,participant_id,version,status,input_hash,input_json,rubric_version,created_at) VALUES (?,?,?,?,'PENDING',?,?,?,CURRENT_TIMESTAMP)",job,input.evaluationId(),input.participantId(),version,hash,data,RUBRIC_VERSION);
        return job;
    }
    public Input getInput(String job){return decode(db.queryForObject("SELECT input_json FROM process_grading_jobs WHERE id=?",String.class,job),Input.class);}
    public List<String> getPendingJobs(){return db.queryForList("SELECT id FROM process_grading_jobs WHERE status='PENDING' ORDER BY created_at LIMIT 10",String.class);}
    public record PendingJob(String id,String participantId,java.time.Instant createdAt) {}
    /** 제출 순서대로 대기 작업(학생마다 마지막 제출본 하나). */
    public List<PendingJob> getPendingJobRows(){return db.query("SELECT id,participant_id,created_at FROM process_grading_jobs WHERE status='PENDING' ORDER BY created_at LIMIT 20",
            (r,n)->new PendingJob(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant()));}
    public boolean claimJob(String id){return db.update("UPDATE process_grading_jobs SET status='RUNNING',started_at=CURRENT_TIMESTAMP,heartbeat_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",id)==1;}
    public void recoverJobs(){db.update("UPDATE process_grading_jobs SET status='FAILED',error_code='WORKER_INTERRUPTED',completed_at=CURRENT_TIMESTAMP WHERE status='RUNNING' AND COALESCE(heartbeat_at,started_at)<(CURRENT_TIMESTAMP - INTERVAL '5' MINUTE)");}
    public void touchJob(String id){db.update("UPDATE process_grading_jobs SET heartbeat_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",id);}
    public void completeJob(String id,String model,Result result){db.update("UPDATE process_grading_jobs SET status=?,model=?,result_json=?,completed_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",result.status(),model,encode(result),id);}
    public void failJob(String id,String code){db.update("UPDATE process_grading_jobs SET status='FAILED',error_code=?,completed_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",code,id);}
    public List<Job> getJobs(String id,String pid){return db.query("SELECT * FROM process_grading_jobs WHERE evaluation_id=? AND participant_id=? ORDER BY version DESC",(r,n)->new Job(r.getString("id"),id,pid,r.getInt("version"),r.getString("status"),r.getString("input_hash"),r.getString("rubric_version"),r.getString("model"),r.getObject("created_at",OffsetDateTime.class).toInstant(),r.getObject("completed_at")==null?null:r.getObject("completed_at",OffsetDateTime.class).toInstant(),r.getString("result_json")==null?null:decode(r.getString("result_json"),Result.class),r.getString("error_code")),id,pid);}
    public Optional<Review> getReview(String job){return db.queryForList("SELECT review_json FROM process_grading_reviews WHERE job_id=? ORDER BY revision DESC LIMIT 1",String.class,job).stream().findFirst().map(s->decode(s,Review.class));}
    public void saveReview(String job,Review review){db.update("INSERT INTO process_grading_reviews(job_id,revision,review_json) VALUES (?,?,?)",job,review.revision(),encode(review));}
    public boolean existsParticipant(String id,String pid){return db.queryForObject("SELECT COUNT(*) FROM participants WHERE evaluation_id=? AND id=?",Integer.class,id,pid)>0;}
    public boolean hasSubmission(String id,String pid){return db.queryForObject("SELECT COUNT(*) FROM submissions WHERE evaluation_id=? AND participant_id=?",Integer.class,id,pid)>0;}
    public String getCurrentSubmissionHash(String id,String pid){return db.queryForObject("SELECT review_token FROM submissions WHERE evaluation_id=? AND participant_id=?",String.class,id,pid);}
    public List<Review> getReviewHistory(String job){return db.queryForList("SELECT review_json FROM process_grading_reviews WHERE job_id=? ORDER BY revision DESC",String.class,job).stream().map(s->decode(s,Review.class)).toList();}
    public void lockJob(String job){db.queryForList("SELECT id FROM process_grading_jobs WHERE id=? FOR UPDATE",job);}
}
