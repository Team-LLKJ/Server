package kr.lit.auth;
import kr.lit.api.ApiError;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
@Service
public class SignupEmailDeliveryService {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    public SignupEmailDeliveryService(ObjectProvider<JavaMailSender> senders,@Value("${lit.auth.mail-from:}") String from) {this.senders=senders;this.from=from;}
    public void sendVerificationCode(String email,String code) {
        var sender=senders.getIfAvailable();
        if(sender==null||from.isBlank())throw new ApiError.Failure(HttpStatus.SERVICE_UNAVAILABLE,"EMAIL_NOT_CONFIGURED","인증메일 발송 설정이 준비되지 않았습니다.");
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);message.setSubject("[LIT] 회원가입 이메일 인증");
        message.setText("LIT 이메일 인증번호: "+code+"\n10분 안에 입력해주세요. 요청하지 않았다면 이 메일을 무시해주세요.");
        try {sender.send(message);}catch(MailException ex) {throw new ApiError.Failure(HttpStatus.BAD_GATEWAY,"EMAIL_DELIVERY_FAILED","인증메일을 발송하지 못했습니다. 잠시 후 다시 시도해주세요.");}
    }
}
