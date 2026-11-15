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
        var f=createFixture(true);assertThat(disclosureByToken(f.id(),f.token()).status()).isEqualTo("PENDING");
        String copied="광합성은 빛 에너지를 이용해 물과 이산화탄소로 포도당을 만드는 과정입니다.";
        for(int i=0;i<3;i++){var r=work.reserve(f.id(),f.token(),f.qid(),new Ask("auto-"+i,"개념 설명해줘"),"gemini",i==2?"model-b":"model-a");work.finish(r,i==0?copied:"개념 응답",null);}
        work.save(f.id(),f.token(),f.qid(),new SaveAnswer(copied+" 나는 잎을 관찰해서 확인했다.",0,AnswerSource.DIRECT,null));
        var preview=work.review(f.id(),f.token());
        // AI 활용 기록은 교사만 봅니다(2026-11-14): 참가자 토큰으로는 조회·저장 모두 403
        for(String path:List.of("/api/student/evaluations/"+f.id()+"/ai-disclosure","/api/evaluations/"+f.id()+"/ai-disclosure")) {
            mvc.perform(get(path).header("X-Participant-Token",f.token())).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("TEACHER_ONLY"));
            mvc.perform(put(path).header("X-Participant-Token",f.token()).contentType("application/json").content("{\"tools\":\"fake\",\"usedParts\":\"fake\",\"reason\":\"fake\"}")).andExpect(status().isForbidden());
        }
        work.submit(f.id(),f.token(),preview.reviewToken());var result=disclosureByToken(f.id(),f.token());
        assertThat(result.status()).isEqualTo("READY");
        String job=db.queryForObject("SELECT id FROM process_grading_jobs WHERE participant_id=?",String.class,f.pid());
        assertThat(gradingRepository.getInput(job).sources()).noneMatch(source->source.kind().equals("DISCLOSURE")).anyMatch(source->source.kind().equals(ProcessGradingRepository.KIND_AUTO_DISCLOSURE));assertThat(result.tools().lines()).hasSize(2);assertThat(result.evidence()).hasSize(3);assertThat(result.conversationIds()).hasSize(3);assertThat(result.reason()).isEqualTo(AutomaticDisclosureAnalyzer.PENDING_PARTS);
        assertThat(disclosureByToken(f.id(),f.token())).isEqualTo(result);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_disclosures WHERE participant_id=?",Integer.class,f.pid())).isZero();
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/ai-disclosure"),teacher)).andExpect(status().isOk()).andExpect(jsonPath("editable").value(false)).andExpect(jsonPath("status").value("READY"));
        assertThat(submissionByToken(f.id(),f.token()).snapshot().path("aiDisclosure").path("tools").asText()).isEqualTo(result.tools());
        // 문장 분석이 끝나면 활용 부분·이유를 서버가 채웁니다(학생 입력 없음). 학생·교사 화면 모두 같은 자동 기록.
        as(teacher);assessment.analysis(f.id(),f.pid());
        var filled=disclosureByToken(f.id(),f.token());
        long first=result.conversationIds().get(0);
        assertThat(filled.usedParts().lines()).hasSize(3);
        assertThat(filled.reason()).startsWith("문항 1 답안 1번째 문장 – AI 답변 인용").contains("AI 답변 #"+first,"자 이상 같음");
        mvc.perform(request(get("/api/teacher/evaluations/"+f.id()+"/students/"+f.pid()+"/ai-disclosure"),teacher)).andExpect(jsonPath("usedParts").value(filled.usedParts())).andExpect(jsonPath("studentDisclosure").doesNotExist());
        String root="/api/teacher/evaluations/"+f.id()+"/students/"+f.pid();
        mvc.perform(body(request(patch(root+"/conversations/"+first+"/purpose"),teacher),
                java.util.Map.of("purpose","REVIEW"))).andExpect(status().isOk());
        assertThat(disclosureByToken(f.id(),f.token()).usedParts()).startsWith("문항 1 – 비판적 검토");
        as(teacher);
        var analysis=assessment.analysis(f.id(),f.pid());
        assessment.labelSentence(f.id(),f.pid(),analysis.sentences().get(0).id(),new AssessmentModels.SentenceLabelInput(null,"DIRECT"));
        assertThat(disclosureByToken(f.id(),f.token()).reason()).startsWith("문항 1 답안 1번째 문장 – 교사가 학생 문장으로 확정");
    }
    @Test void deadlineAndTeacherEndGenerateEvenWithoutAiOrStartedSubmission(){
        var f=createFixture(true);db.update("UPDATE evaluations SET expires_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP),ends_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id=?",f.id());work.finishEvaluation(f.id(),true);
        assertThat(disclosureByToken(f.id(),f.token()).status()).isEqualTo("READY");assertThat(disclosureByToken(f.id(),f.token()).tools()).isEmpty();
        var waiting=createFixture(false);work.finishEvaluation(waiting.id(),false);assertThat(disclosureByToken(waiting.id(),waiting.token()).status()).isEqualTo("READY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM submissions WHERE participant_id=?",Integer.class,waiting.pid())).isZero();
    }
    @Test void failedAiRequestsDoNotClaimSuccessfulModelUse(){
        var f=createFixture(true);var r=work.reserve(f.id(),f.token(),f.qid(),new Ask("failed","개념 설명해줘"),"gemini","model");work.finish(r,null,"UNAVAILABLE");submitFixture(f);
        var result=disclosureByToken(f.id(),f.token());assertThat(result.tools()).isEmpty();assertThat(result.reason()).isEqualTo(AutomaticDisclosureAnalyzer.PENDING_PARTS);assertThat(result.evidence()).hasSize(1);
    }
    @Test void generationFailureIsPersistedAndNotRetried(){
        var f=createFixture(true);var malformed=json.createObjectNode();malformed.putArray("conversations").addObject().put("questionText","missing id");
        var failed=disclosure.generateDisclosure(f.id(),f.pid(),malformed);assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("DISCLOSURE_GENERATION_FAILED");
        assertThat(disclosure.generateDisclosure(f.id(),f.pid(),json.createObjectNode())).isEqualTo(failed);
    }
}
