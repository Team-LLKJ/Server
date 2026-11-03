package kr.lit.auth;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import kr.lit.api.ApiError;
@Service
public class EmailChangeDeliveryService {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    public EmailChangeDeliveryService(ObjectProvider<JavaMailSender> senders,@Value("${lit.auth.mail-from:}") String from) {this.senders=senders;this.from=from;}
    public void sendVerificationCode(String email,String code) {
        var sender=senders.getIfAvailable();
        if(sender==null||from.isBlank())throw new ApiError.Failure(HttpStatus.SERVICE_UNAVAILABLE,"EMAIL_NOT_CONFIGURED","인증메일 발송 설정이 준비되지 않았습니다.");
        try {
            var message=sender.createMimeMessage();var helper=new MimeMessageHelper(message,true,"UTF-8");
            helper.setFrom(from);helper.setTo(email);helper.setSubject("[LIT] 이메일 주소 변경 인증");
            String text="LIT 이메일 변경 인증번호: "+code+"\n10분 안에 입력해주세요. 요청하지 않았다면 이 메일을 무시해주세요.";
            String html="<!doctype html><html lang=\"ko\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body style=\"margin:0;padding:24px;background:#F3F7FC;font-family:Arial,sans-serif;color:#172B4D\"><table role=\"presentation\" style=\"width:100%;max-width:560px;margin:auto;background:#FFFFFF;border-top:6px solid #007BFF\"><tr><td style=\"padding:28px\"><h1 style=\"color:#007BFF\">LIT</h1><h2>이메일 주소 변경을 인증해 주세요</h2><p>아래 인증번호를 이메일 변경 화면에 입력해 주세요.</p><p style=\"padding:24px 12px;background:#EDF5FF;color:#007BFF;font-size:36px;font-weight:bold;letter-spacing:6px;text-align:center\">"+HtmlUtils.htmlEscape(code)+"</p><p>인증번호는 10분 동안 유효합니다.</p><p>본인이 요청하지 않았다면 이 메일을 무시해 주세요.</p></td></tr></table></body></html>";
            helper.setText(text,html);sender.send(message);
        }catch(MessagingException|MailException ex) {throw new ApiError.Failure(HttpStatus.BAD_GATEWAY,"EMAIL_DELIVERY_FAILED","인증메일을 발송하지 못했습니다. 잠시 후 다시 시도해주세요.");}
    }
}
