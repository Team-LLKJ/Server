package kr.lit.api;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.io.ByteArrayOutputStream;
@RestController
@RequestMapping("/api")
class ApiController {
 private final EvaluationService service;
 @Value("${lit.join-base-url}") private String baseUrl;
 ApiController(EvaluationService service){this.service=service;}
 record Create(@NotBlank @Size(max=100) String title,@Min(1) @Max(1440) int expiresInMinutes,@Size(max=10000) String evaluationContent,@Size(max=10000) String guidelines){}
 record Join(String pin,String nickname,@Size(max=64) String participantToken){}
 @PostMapping("/teacher/evaluations") @ResponseStatus(HttpStatus.CREATED)
 Object create(@Valid @RequestBody Create body){return service.create(body.title(),body.expiresInMinutes(),Guidelines.resolve(body.evaluationContent(),body.guidelines()));}
 @GetMapping("/teacher/evaluations")
 Object mine(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.mine(page,size);}
 @GetMapping("/teacher/evaluations/{id}") Object details(@PathVariable String id){return service.details(id);}
 @GetMapping("/teacher/evaluations/{id}/participants") Object list(@PathVariable String id){return service.lobby(id);}
 @PostMapping("/teacher/evaluations/{id}/start") Object start(@PathVariable String id){return service.start(id);}
 @PostMapping("/teacher/evaluations/{id}/end") Object end(@PathVariable String id){return service.end(id);}
 @GetMapping(value="/teacher/evaluations/{id}/qr",produces=MediaType.IMAGE_PNG_VALUE)
 byte[] qr(@PathVariable String id) throws Exception {
  var e=service.details(id); var matrix=new QRCodeWriter().encode(baseUrl+"?pin="+e.pin(),BarcodeFormat.QR_CODE,300,300);
  var out=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(matrix,"PNG",out);return out.toByteArray();
 }
 @GetMapping("/participation/verify") Object verify(@RequestParam String pin){return service.verify(pin);}
 @PostMapping("/participation/join") Object join(@Valid @RequestBody Join body){return service.join(body.pin(),body.nickname(),body.participantToken());}
 @GetMapping("/evaluations/{id}/state") Object state(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token){return service.participant(id,token,false);}
 @PostMapping("/evaluations/{id}/heartbeat") Object heartbeat(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token){return service.participant(id,token,false);}
 @PostMapping("/evaluations/{id}/leave") Object leave(@PathVariable String id,@RequestHeader(value="X-Participant-Token",required=false) String token){return service.participant(id,token,true);}
}