package kr.lit.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AutomaticDisclosureApiTest extends AuthTestSupport {
    @Autowired AutomaticDisclosureService disclosure;
    @Autowired ProcessGradingRepository gradingRepository;
    @Autowired AssessmentService assessment;
    record Fixture(String id,String pid,String token,String qid){}
    Fixture createFixture(boolean start){
        as(teacher);var e=evaluations.create("자동 공개",60);var q=work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"문항","개념 설명")))).questions().get(0).id();
        var p=evaluations.join(e.pin(),"학생",null);if(start)evaluations.start(e.id());return new Fixture(e.id(),p.participantId(),p.participantToken(),q);
    }
    void submitFixture(Fixture f){var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());}
    @Test void submissionGeneratesDistinctModelsAndStableEvidenceAndIgnoresStudentWrites()throws Exception {
        var f=createFixture(true);assertThat(work.disclosure(f.id(),f.token()).status()).isEqualTo("PENDING");
        String copied="광합성은 빛 에너지를 이용해 물과 이산화탄소로 포도당을 만드는 과정입니다.";
        for(int i=0;i<3;i++){var r=work.reserve(f.id(),f.token(),f.qid(),new Ask("auto-"+i,"개념 설명해줘"),"gemini",i==2?"model-b":"model-a");work.finish(r,i==0?copied:"개념 응답",null);}
        work.save(f.id(),f.token(),f.qid(),new SaveAnswer(copied+" 나는 잎을 관찰해서 확인했다.",0,AnswerSource.DIRECT,null));
        var preview=work.review(f.id(),f.token());var ignored=work.saveDisclosure(f.id(),f.token(),new Disclosure("fake","fake","fake"));assertThat(ignored.editable()).isFalse();
        work.submit(f.id(),f.token(),preview.reviewToken());var result=work.disclosure(f.id(),f.token());
        assertThat(result.status()).isEqualTo("READY");
        String job=db.queryForObject("SELECT id FROM process_grading_jobs WHERE participant_id=?",String.class,f.pid());
        assertThat(gradingRepository.getInput(job).sources()).noneMatch(source->source.kind().equals("DISCLOSURE")).anyMatch(source->source.kind().equals(ProcessGradingRepository.KIND_AUTO_DISCLOSURE));assertThat(result.tools().lines()).hasSize(2);assertThat(result.evidence()).hasSize(3);assertThat(result.conversationIds()).hasSize(3);assertThat(result.usedParts()).isEqualTo(AutomaticDisclosureAnalyzer.PENDING_PARTS);
        work.saveDisclosure(f.id(),f.token(),new Disclosure("fake","fake","fake"));assertThat(work.disclosure(f.id(),f.token())).isEqualTo(result);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_disclosures WHERE participant_id=?",Integer.class,f.pid())).isZero();
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/ai-disclosure"),teacher)).andExpect(jsonPath("editable").value(false)).andExpect(jsonPath("status").value("READY"));
        assertThat(work.submission(f.id(),f.token()).snapshot().path("aiDisclosure").path("tools").asText()).isEqualTo(result.tools());
        // 문장 분석이 끝나면 활용 부분·이유를 서버가 채웁니다(학생 입력 없음). 학생·교사 화면 모두 같은 자동 기록.
        as(teacher);assessment.analysis(f.id(),f.pid());
        var filled=work.disclosure(f.id(),f.token());
        long first=result.conversationIds().get(0);
        assertThat(filled.usedParts()).isEqualTo("문항 1: '"+copied.substring(0,30)+"…' (AI 답변 #"+first+")");
        assertThat(filled.reason()).startsWith("문항 1 '"+copied.substring(0,30)+"…': AI 답변 #"+first).contains("자 이상 같음");
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/ai-disclosure"),teacher)).andExpect(jsonPath("usedParts").value(filled.usedParts())).andExpect(jsonPath("studentDisclosure").doesNotExist());
    }
    @Test void deadlineAndTeacherEndGenerateEvenWithoutAiOrStartedSubmission(){
        var f=createFixture(true);db.update("UPDATE evaluations SET expires_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP),ends_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id=?",f.id());work.finishEvaluation(f.id(),true);
        assertThat(work.disclosure(f.id(),f.token()).status()).isEqualTo("READY");assertThat(work.disclosure(f.id(),f.token()).tools()).isEmpty();
        var waiting=createFixture(false);work.finishEvaluation(waiting.id(),false);assertThat(work.disclosure(waiting.id(),waiting.token()).status()).isEqualTo("READY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,waiting.pid())).isZero();
    }
    @Test void failedAiRequestsDoNotClaimSuccessfulModelUse(){
        var f=createFixture(true);var r=work.reserve(f.id(),f.token(),f.qid(),new Ask("failed","개념 설명해줘"),"gemini","model");work.finish(r,null,"UNAVAILABLE");submitFixture(f);
        var result=work.disclosure(f.id(),f.token());assertThat(result.tools()).isEmpty();assertThat(result.usedParts()).isEqualTo(AutomaticDisclosureAnalyzer.PENDING_PARTS);assertThat(result.evidence()).hasSize(1);
    }
    @Test void generationFailureIsPersistedAndNotRetried(){
        var f=createFixture(true);var malformed=json.createObjectNode();malformed.putArray("conversations").addObject().put("questionText","missing id");
        var failed=disclosure.generateDisclosure(f.id(),f.pid(),malformed);assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("DISCLOSURE_GENERATION_FAILED");
        assertThat(disclosure.generateDisclosure(f.id(),f.pid(),json.createObjectNode())).isEqualTo(failed);
    }
}
