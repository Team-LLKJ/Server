package kr.lit.api;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;
@RestControllerAdvice
public class ApiError {
 public static class Failure extends RuntimeException {
  public final HttpStatus status; public final String code;
  public Failure(HttpStatus status,String code,String message){super(message);this.status=status;this.code=code;}
 }
 @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,org.springframework.web.servlet.NoHandlerFoundException.class})
 ResponseEntity<?> notFound(Exception e){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code","NOT_FOUND","message","API 경로를 찾을 수 없습니다."));}
 @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
 ResponseEntity<?> methodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException e){
  var headers=new HttpHeaders();if(e.getSupportedHttpMethods()!=null)headers.setAllow(e.getSupportedHttpMethods());
  return new ResponseEntity<>(Map.of("code","METHOD_NOT_ALLOWED","message","지원하지 않는 HTTP 메서드입니다."),headers,HttpStatus.METHOD_NOT_ALLOWED);
 }
 @ExceptionHandler(Failure.class)
 ResponseEntity<?> failure(Failure e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
 @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class, jakarta.validation.ConstraintViolationException.class, org.springframework.web.method.annotation.HandlerMethodValidationException.class})
 ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","입력 형식과 길이를 확인해주세요."));}
}
