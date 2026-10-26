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
 record Create(@NotBlank @Size(max=100) String title,@Min(1) @Max(1440) int expiresInMinutes){}
 record Join(String pin,String nickname,@Size(max=100) String participantToken){}
 @PostMapping("/teacher/evaluations") @ResponseStatus(HttpStatus.CREATED)
 Object create(@Valid @RequestBody Create body){return service.create(body.title(),body.expiresInMinutes());}
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
@Component
class ApiAccess implements WebMvcConfigurer, HandlerInterceptor {
 private final String key;
 private record Attempts(long resetAt,int count){}
 private final java.util.Map<String,Attempts> attempts=new java.util.HashMap<>();
 private synchronized void checkAttempts(String address){
  long now=System.currentTimeMillis();
  attempts.entrySet().removeIf(entry->entry.getValue().resetAt()<=now);
  Attempts previous=attempts.get(address);
  if(previous==null&&attempts.size()>=10000)throw new ApiError.Failure(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","잠시 후 다시 시도해주세요.");
  if(previous!=null&&previous.count()>=30)throw new ApiError.Failure(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","PIN 입력은 1분에 30회까지 가능합니다.");
  attempts.put(address,new Attempts(previous==null?now+60000:previous.resetAt(),previous==null?1:previous.count()+1));
 }
 ApiAccess(@Value("${lit.teacher-key}") String key){this.key=key;}
 public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).addPathPatterns("/api/**");}
 public boolean preHandle(HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler){
  response.setHeader("Cache-Control","no-store");
  if(request.getRequestURI().startsWith("/api/participation/"))checkAttempts(request.getRemoteAddr());
  if(request.getRequestURI().startsWith("/api/teacher/")){
   if(key.isBlank())throw new ApiError.Failure(HttpStatus.SERVICE_UNAVAILABLE,"TEACHER_KEY_NOT_CONFIGURED","서버의 LIT_TEACHER_KEY를 설정해주세요.");
   String supplied=request.getHeader("Authorization");
   if(supplied==null||!MessageDigest.isEqual(("Bearer "+key).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
    throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"TEACHER_UNAUTHORIZED","교사 인증이 필요합니다.");
  }
  return true;
 }
}
