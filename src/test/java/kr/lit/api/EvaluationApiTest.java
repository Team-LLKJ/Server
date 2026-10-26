package kr.lit.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class EvaluationApiTest extends AuthTestSupport {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate db;
 @Autowired EvaluationService service;
 JsonNode request(String path,String body) throws Exception {
  return mapper.readTree(mvc.perform(post(path).header("Authorization","Bearer "+teacher.accessToken()).contentType("application/json").content(body)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
 }
 JsonNode create() throws Exception{return request("/api/teacher/evaluations","{\"title\":\"수학 평가\",\"expiresInMinutes\":60}");}
 @Test void fullFlowAndReconnect() throws Exception {
  var e=create();String id=e.get("id").asText(),pin=e.get("pin").asText();
  assertThat(pin).matches("[0-9]{6}");
  mvc.perform(get("/api/teacher/evaluations/"+id+"/qr").header("Authorization","Bearer "+teacher.accessToken())).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
  mvc.perform(post("/api/teacher/evaluations/"+id+"/start").header("Authorization","Bearer "+teacher.accessToken())).andExpect(status().isConflict()).andExpect(jsonPath("code").value("NO_PARTICIPANTS"));
  var joined=request("/api/participation/join","{\"pin\":\""+pin+"\",\"nickname\":\"학생1\"}");
  String token=joined.get("participantToken").asText();assertThat(token).hasSize(64);
  mvc.perform(post("/api/participation/join").contentType("application/json").content("{\"pin\":\""+pin+"\",\"nickname\":\"학생1\"}")).andExpect(status().isConflict());
  db.update("UPDATE participants SET last_seen=DATEADD('SECOND',-40,CURRENT_TIMESTAMP) WHERE evaluation_id=?",id);
  mvc.perform(get("/api/teacher/evaluations/"+id+"/participants").header("Authorization","Bearer "+teacher.accessToken())).andExpect(jsonPath("participants[0].presence").value("DISCONNECTED"));
  mvc.perform(post("/api/evaluations/"+id+"/heartbeat").header("X-Participant-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("participants[0].presence").value("CONNECTED"));
  request("/api/teacher/evaluations/"+id+"/start","{}");
  mvc.perform(post("/api/teacher/evaluations/"+id+"/start").header("Authorization","Bearer "+teacher.accessToken())).andExpect(status().isConflict());
  mvc.perform(get("/api/evaluations/"+id+"/state").header("X-Participant-Token",token)).andExpect(jsonPath("screen").value("SOLVING"));
  mvc.perform(post("/api/evaluations/"+id+"/leave").header("X-Participant-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("participantCount").value(0));
  var rejoined=request("/api/participation/join","{\"pin\":\""+pin+"\",\"participantToken\":\""+token+"\"}");
  assertThat(rejoined.get("participantId")).isEqualTo(joined.get("participantId"));
  assertThat(rejoined.at("/lobby/participantCount").asInt()).isEqualTo(1);
  mvc.perform(post("/api/participation/join").contentType("application/json").content("{\"pin\":\""+pin+"\",\"nickname\":\"늦은학생\"}")).andExpect(status().isConflict());
  request("/api/teacher/evaluations/"+id+"/end","{}");
  mvc.perform(get("/api/participation/verify").param("pin",pin)).andExpect(status().isConflict()).andExpect(jsonPath("code").value("EVALUATION_ENDED"));
 }
 @Test void validationAuthenticationAndExpiry() throws Exception {
  mvc.perform(post("/api/teacher/evaluations").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/participation/verify").param("pin"," ")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/teacher/evaluations").header("Authorization","Bearer "+teacher.accessToken()).contentType("application/json").content("{\"title\":\" \",\"expiresInMinutes\":0}")).andExpect(status().isBadRequest());
  var e=create();String id=e.get("id").asText(),pin=e.get("pin").asText();
  mvc.perform(get("/api/evaluations/"+id+"/state")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/participation/join").contentType("application/json").content("{\"pin\":\""+pin+"\",\"nickname\":\"   \"}")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/participation/join").contentType("application/json").content("{\"pin\":\""+pin+"\",\"nickname\":\""+"가".repeat(21)+"\"}")).andExpect(status().isBadRequest());
  db.update("UPDATE evaluations SET expires_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?",id);
  mvc.perform(get("/api/participation/verify").param("pin",pin)).andExpect(status().isGone()).andExpect(jsonPath("code").value("PIN_EXPIRED"));
 }
 @Test void concurrentStartsHaveOneWinner() throws Exception {
  as(teacher);var e=service.create("동시성 평가",60);service.join(e.pin(),"학생",null);
  var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  Callable<Boolean> start=()->{as(teacher);gate.await();try{service.start(e.id());return true;}catch(ApiError.Failure ex){return false;}};
  try{var first=executor.submit(start);var second=executor.submit(start);gate.countDown();assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(true,false);}
  finally{executor.shutdownNow();}
 }
 @Test void leadingZeroPinIsPreserved() throws Exception {
  String id=UUID.randomUUID().toString();db.update("INSERT INTO evaluations(id,title,pin,state,expires_at) VALUES (?,?,?,'WAITING',DATEADD('HOUR',1,CURRENT_TIMESTAMP))",id,"앞자리 0","000001");
  mvc.perform(get("/api/participation/verify").param("pin"," 000001 ")).andExpect(status().isOk()).andExpect(jsonPath("pin").value("000001"));
 }
}
