package kr.lit.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class EvaluationApiTest extends AuthTestSupport {
    @Test void fullFlowAndAccountReconnect() throws Exception {
        var e=create();String id=e.get("id").asText(),pin=e.get("pin").asText();String base="/api/teacher/evaluations/"+id;
        assertThat(pin).matches("[0-9]{6}");
        mvc.perform(request(get(base+"/qr"),teacher)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(request(post(base+"/start"),teacher)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("NO_PARTICIPANTS"));
        var joined=join(pin,"학생1",student);assertThat(joined.has("participantToken")).isFalse();
        mvc.perform(request(body(post("/api/participation/join"),Map.of("pin",pin,"nickname","학생1")),otherStudent)).andExpect(status().isConflict());
        db.update("UPDATE participants SET last_seen=DATEADD('SECOND',-40,CURRENT_TIMESTAMP) WHERE evaluation_id=?",id);
        mvc.perform(request(get(base+"/participants"),teacher)).andExpect(jsonPath("participants[0].presence").value("DISCONNECTED"));
        mvc.perform(request(post("/api/evaluations/"+id+"/heartbeat"),student)).andExpect(jsonPath("participants[0].presence").value("CONNECTED"));
        ok(request(post(base+"/start"),teacher));
        mvc.perform(request(post(base+"/start"),teacher)).andExpect(status().isConflict());
        mvc.perform(request(get("/api/evaluations/"+id+"/state"),student)).andExpect(jsonPath("screen").value("SOLVING"));
        mvc.perform(request(post("/api/evaluations/"+id+"/leave"),student)).andExpect(jsonPath("participantCount").value(0));
        mvc.perform(request(get("/api/evaluations/"+id+"/state"),student)).andExpect(status().isUnauthorized());
        var rejoined=join(pin,"바뀌지 않는 이름",student);
        assertThat(rejoined.get("participantId")).isEqualTo(joined.get("participantId"));
        assertThat(rejoined.at("/lobby/participantCount").asInt()).isEqualTo(1);
        assertThat(rejoined.at("/lobby/participants/0/nickname").asText()).isEqualTo("학생1");
        mvc.perform(request(body(post("/api/participation/join"),Map.of("pin",pin,"nickname","새 학생")),otherStudent)).andExpect(status().isConflict());
        ok(request(post(base+"/end"),teacher));
        mvc.perform(request(get("/api/participation/verify").param("pin",pin),null)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("EVALUATION_ENDED"));
    }
    @Test void validationRolesAndExpiry() throws Exception {
        mvc.perform(request(body(post("/api/teacher/evaluations"),Map.of()),null)).andExpect(status().isUnauthorized());
        mvc.perform(request(body(post("/api/teacher/evaluations"),Map.of()),student)).andExpect(status().isForbidden());
        mvc.perform(request(get("/api/participation/verify").param("pin"," "),null)).andExpect(status().isBadRequest());
        mvc.perform(request(body(post("/api/teacher/evaluations"),Map.of("title"," ","expiresInMinutes",0)),teacher)).andExpect(status().isBadRequest());
        var e=create();String id=e.get("id").asText(),pin=e.get("pin").asText();
        mvc.perform(request(body(post("/api/participation/join"),Map.of("pin",pin,"nickname"," ")),student)).andExpect(status().isBadRequest());
        mvc.perform(request(body(post("/api/participation/join"),Map.of("pin",pin,"nickname","가".repeat(21))),student)).andExpect(status().isBadRequest());
        db.update("UPDATE evaluations SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",id);
        mvc.perform(request(get("/api/participation/verify").param("pin",pin),null)).andExpect(status().isGone());
    }
    @Test void concurrentStartsHaveOneWinner() throws Exception {
        as(teacher);var e=evaluations.create("동시성",60);as(student);evaluations.join(e.pin(),"학생",student.user().id());
        var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        Callable<Boolean> start=()->{as(teacher);gate.await();try{evaluations.start(e.id());return true;}catch(ApiError.Failure ex){return false;}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}};
        try{var first=executor.submit(start);var second=executor.submit(start);gate.countDown();assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(true,false);}
        finally{executor.shutdownNow();}
    }
    @Test void leadingZeroPinAndLegacyOwnership() throws Exception {
        String id=UUID.randomUUID().toString();db.update("INSERT INTO evaluations(id,title,pin,state,expires_at) VALUES (?,?,?,'WAITING',DATEADD('HOUR',1,CURRENT_TIMESTAMP))",id,"앞자리 0","000001");
        mvc.perform(request(get("/api/participation/verify").param("pin"," 000001 "),null)).andExpect(jsonPath("pin").value("000001"));
        mvc.perform(request(get("/api/teacher/evaluations/"+id),teacher)).andExpect(status().isNotFound());
    }
}
