package kr.lit.api;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;
@RestControllerAdvice
class ApiError {
 static class Failure extends RuntimeException {
  final HttpStatus status; final String code;
  Failure(HttpStatus status,String code,String message){super(message);this.status=status;this.code=code;}
 }
 @ExceptionHandler(Failure.class)
 ResponseEntity<?> failure(Failure e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
 @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class, jakarta.validation.ConstraintViolationException.class, org.springframework.web.method.annotation.HandlerMethodValidationException.class})
 ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","입력 형식과 길이를 확인해주세요."));}
}
