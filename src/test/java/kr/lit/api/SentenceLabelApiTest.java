package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static kr.lit.api.WorkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 3단계: 교사의 문장 판정 수정이 비율에 바로 반영되고, 보정용으로 알고리즘 판정·설정과 함께 저장되는지. */
class SentenceLabelApiTest extends AuthTestSupport {
    static final String AI="청년층은 더 많은 취업 기회를 찾아 수도권이나 대도시로 이동하는 경향이 강합니다.";
    static final String PARAPHRASE="젊은 사람들은 취직할 곳이 많은 서울 근처나 큰 도시로 떠나는 경우가 많다.";
    record Fixture(String id,String pid,String token) {}
    Fixture fixture(boolean submit) {
        as(teacher);var e=evaluations.create("문장 판정",60);
        var q=work.configure(e.id(),new Configure(AiPolicy.FULL,List.of(new QuestionInput(1,"문항","지방 인구 감소의 원인을 설명하세요.")))).questions().get(0).id();
        var p=evaluations.join(e.pin(),"학생",null);evaluations.start(e.id());
        var turn=work.reserve(e.id(),p.participantToken(),q,new Ask("r1","왜 줄어들어?"),"stub","test");work.finish(turn,AI,null);
        work.save(e.id(),p.participantToken(),q,new SaveAnswer("<p>"+AI+" "+PARAPHRASE+"</p>",0,AnswerSource.DIRECT,null));
        if(submit){var review=work.review(e.id(),p.participantToken());work.submit(e.id(),p.participantToken(),review.reviewToken());}
        as(teacher);return new Fixture(e.id(),p.participantId(),p.participantToken());
    }
    String root(Fixture f){return "/api/teacher/evaluations/"+f.id()+"/participants/"+f.pid();}
    JsonNode sentence(JsonNode analysis,String text){for(var s:analysis.path("sentences"))if(s.path("text").asText().equals(text))return s;throw new AssertionError(text);}

    @Test void teacherLabelsOverrideAlgorithmAndAreStoredForCalibration() throws Exception {
        var f=fixture(true);
        var before=ok(request(get(root(f)+"/analysis"),teacher));
        var copied=sentence(before,AI);var paraphrase=sentence(before,PARAPHRASE);
        assertThat(copied.path("reason").asText()).isEqualTo("SPAN_MATCH");assertThat(paraphrase.path("source").asText()).isEqualTo("DIRECT");
        int aiBefore=before.at("/contribution/aiReferenced").asInt();

        // 놓친 바꿔 쓴 문장을 교사가 AI로 표시 → 비율 즉시 증가
        var labeled=ok(request(body(patch(root(f)+"/sentences/"+paraphrase.path("id").asText()),Map.of("label","AI")),teacher));
        var p=sentence(labeled,PARAPHRASE);
        assertThat(p.path("source").asText()).isEqualTo("AI_REFERENCED");assertThat(p.path("reason").asText()).isEqualTo("TEACHER");assertThat(p.path("teacherLabel").asText()).isEqualTo("AI");
        assertThat(labeled.at("/contribution/aiReferenced").asInt()).isEqualTo(100);
        assertThat(ok(request(get(root(f)+"/analysis"),teacher)).at("/contribution/aiReferenced").asInt()).isEqualTo(100);

        // 오탐 정정: 알고리즘이 AI로 본 문장을 DIRECT로
        var corrected=ok(request(body(patch(root(f)+"/sentences/"+copied.path("id").asText()),Map.of("label","DIRECT")),teacher));
        var c=sentence(corrected,AI);
        assertThat(c.path("source").asText()).isEqualTo("DIRECT");assertThat(c.path("reason").isNull()).isTrue();assertThat(c.path("teacherLabel").asText()).isEqualTo("DIRECT");
        assertThat(corrected.at("/contribution/aiReferenced").asInt()).isLessThan(100).isPositive();
        assertThat(aiBefore).isPositive();

        // 마지막 판정이 적용되고, 기록은 모두 남습니다(그때의 알고리즘 판정 포함).
        ok(request(body(patch(root(f)+"/sentences/"+paraphrase.path("id").asText()),Map.of("label","DIRECT")),teacher));
        var rows=db.queryForList("SELECT sentence_text,teacher_label,algorithm_source,algorithm_reason,longest_span,config_json FROM sentence_labels WHERE participant_id=? ORDER BY id",f.pid());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("SENTENCE_TEXT",PARAPHRASE).containsEntry("TEACHER_LABEL","AI").containsEntry("ALGORITHM_SOURCE","DIRECT");
        assertThat(rows.get(1)).containsEntry("TEACHER_LABEL","DIRECT").containsEntry("ALGORITHM_SOURCE","AI_REFERENCED").containsEntry("ALGORITHM_REASON","SPAN_MATCH");
        assertThat((Integer)rows.get(1).get("LONGEST_SPAN")).isGreaterThanOrEqualTo(20);
        assertThat(rows.get(1).get("CONFIG_JSON").toString()).contains("\"minSpanLength\":20","\"promptVersion\"","\"aiReferencedThreshold\":0.4");
        assertThat(ok(request(get(root(f)+"/analysis"),teacher)).at("/contribution/aiReferenced").asInt()).isZero();
    }
    @Test void rejectsInvalidLabelsUnknownSentencesOtherTeachersAndUnsubmitted() throws Exception {
        var f=fixture(true);
        String id=ok(request(get(root(f)+"/analysis"),teacher)).at("/sentences/0/id").asText();
        mvc.perform(request(body(patch(root(f)+"/sentences/"+id),Map.of("label","MAYBE")),teacher)).andExpect(status().isBadRequest());
        mvc.perform(request(body(patch(root(f)+"/sentences/q9-p9-s9"),Map.of("label","AI")),teacher)).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("SENTENCE_NOT_FOUND"));
        mvc.perform(request(body(patch(root(f)+"/sentences/"+id),Map.of("label","AI")),newTeacher())).andExpect(status().isNotFound());
        var open=fixture(false);
        mvc.perform(request(body(patch(root(open)+"/sentences/q1-p1-s1"),Map.of("label","AI")),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("SUBMISSION_REQUIRED"));
    }
}
