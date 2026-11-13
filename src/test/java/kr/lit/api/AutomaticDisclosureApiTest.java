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
    record Fixture(String id,String pid,String token,String qid){}
    Fixture createFixture(boolean start){
        as(teacher);var e=evaluations.create("자동 공개",60);var q=work.configure(e.id(),new Configure(AiPolicy.HINT_ONLY,List.of(new QuestionInput(1,"문항","개념 설명")))).questions().get(0).id();
        var p=evaluations.join(e.pin(),"학생",null);if(start)evaluations.start(e.id());return new Fixture(e.id(),p.participantId(),p.participantToken(),q);
    }
    void submitFixture(Fixture f){var preview=work.review(f.id(),f.token());work.submit(f.id(),f.token(),preview.reviewToken());}
    @Test void submissionGeneratesDistinctModelsAndStableEvidenceAndKeepsStudentWrites()throws Exception {
        var f=createFixture(true);assertThat(work.disclosure(f.id(),f.token()).status()).isEqualTo("PENDING");
        for(int i=0;i<3;i++){var r=work.reserve(f.id(),f.token(),f.qid(),new Ask("auto-"+i,"개념 설명해줘"),"gemini",i==2?"model-b":"model-a");work.finish(r,"개념 응답",null);}
        var preview=work.review(f.id(),f.token());var written=work.saveDisclosure(f.id(),f.token(),new Disclosure("LIT 내장 AI","문항 1 첫 문단","개념을 확인하려고"));
        assertThat(written.source()).isEqualTo("STUDENT_REPORTED");assertThat(written.editable()).isTrue();assertThat(written.reason()).isEqualTo("개념을 확인하려고");
        work.submit(f.id(),f.token(),preview.reviewToken());var result=work.disclosure(f.id(),f.token());
        // 제출해도 학생이 쓴 설명은 덮어쓰지 않습니다. 자동 기록은 스냅샷과 교사 화면에 그대로 있고, 근거 목록은 학생 화면에도 붙습니다.
        assertThat(result.status()).isEqualTo("READY");assertThat(result.source()).isEqualTo("STUDENT_REPORTED");assertThat(result.tools()).isEqualTo("LIT 내장 AI");
        assertThat(result.evidence()).hasSize(3);assertThat(result.conversationIds()).hasSize(3);
        var auto=work.submission(f.id(),f.token()).snapshot().path("aiDisclosure");
        assertThat(auto.path("source").asText()).isEqualTo("SYSTEM_GENERATED");assertThat(auto.path("tools").asText().lines()).hasSize(2);assertThat(auto.path("usedParts").asText()).contains("문항 1","개념 확인");
        // 투명성 판정 입력: 학생 설명(DISCLOSURE) + 자동 기록(AUTO_DISCLOSURE, auto-disclosure:)
        String job=db.queryForObject("SELECT id FROM process_grading_jobs WHERE participant_id=? ORDER BY version DESC LIMIT 1",String.class,f.pid());
        var sources=gradingRepository.getInput(job).sources();
        assertThat(sources).anyMatch(s->s.kind().equals("DISCLOSURE")&&s.id().equals("disclosure:REASON")&&s.text().equals("개념을 확인하려고"));
        assertThat(sources).anyMatch(s->s.kind().equals(ProcessGradingRepository.KIND_AUTO_DISCLOSURE)&&s.id().startsWith("auto-disclosure:"));
        // 제출 후(완료 화면)에 고치면 저장되고, 과정 채점이 새 설명으로 다시 대기열에 들어갑니다.
        int jobs=db.queryForObject("SELECT COUNT(*) FROM process_grading_jobs WHERE participant_id=?",Integer.class,f.pid());
        assertThat(work.saveDisclosure(f.id(),f.token(),new Disclosure("LIT 내장 AI","문항 1 첫 문단","개념을 확인하고 내 말로 바꿨다")).reason()).isEqualTo("개념을 확인하고 내 말로 바꿨다");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM process_grading_jobs WHERE participant_id=?",Integer.class,f.pid())).isEqualTo(jobs+1);
        work.saveDisclosure(f.id(),f.token(),new Disclosure("LIT 내장 AI","문항 1 첫 문단","개념을 확인하고 내 말로 바꿨다"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM process_grading_jobs WHERE participant_id=?",Integer.class,f.pid())).isEqualTo(jobs+1); // 같은 내용이면 다시 넣지 않음
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/ai-disclosure"),teacher)).andExpect(jsonPath("editable").value(false)).andExpect(jsonPath("status").value("READY"))
                .andExpect(jsonPath("source").value("SYSTEM_GENERATED")).andExpect(jsonPath("studentDisclosure.reason").value("개념을 확인하고 내 말로 바꿨다"));
    }
    @Test void deadlineAndTeacherEndGenerateEvenWithoutAiOrStartedSubmission(){
        var f=createFixture(true);db.update("UPDATE evaluations SET expires_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP),ends_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id=?",f.id());work.finishEvaluation(f.id(),true);
        assertThat(work.disclosure(f.id(),f.token()).status()).isEqualTo("READY");assertThat(work.disclosure(f.id(),f.token()).tools()).isEmpty();
        var waiting=createFixture(false);work.finishEvaluation(waiting.id(),false);assertThat(work.disclosure(waiting.id(),waiting.token()).status()).isEqualTo("READY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,waiting.pid())).isZero();
    }
    @Test void failedAiRequestsDoNotClaimSuccessfulModelUse(){
        var f=createFixture(true);var r=work.reserve(f.id(),f.token(),f.qid(),new Ask("failed","개념 설명해줘"),"gemini","model");work.finish(r,null,"UNAVAILABLE");submitFixture(f);
        var result=work.disclosure(f.id(),f.token());assertThat(result.tools()).isEmpty();assertThat(result.reason()).contains("완료 응답 0건","실패·미완료 1건");assertThat(result.evidence()).hasSize(1);
    }
    @Test void generationFailureIsPersistedAndNotRetried(){
        var f=createFixture(true);var malformed=json.createObjectNode();malformed.putArray("conversations").addObject().put("questionText","missing id");
        var failed=disclosure.generateDisclosure(f.id(),f.pid(),malformed);assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("DISCLOSURE_GENERATION_FAILED");
        assertThat(disclosure.generateDisclosure(f.id(),f.pid(),json.createObjectNode())).isEqualTo(failed);
    }
}
