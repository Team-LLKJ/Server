package kr.lit.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import kr.lit.api.ApiError;

@Component
public class ResetMailer {
    private final ObjectProvider<JavaMailSender> mail;
    private final String from,baseUrl,host;
    public ResetMailer(ObjectProvider<JavaMailSender> mail,@Value("${lit.auth.mail-from:}") String from,
                       @Value("${lit.auth.reset-base-url}") String baseUrl,@Value("${spring.mail.host:}") String host) {
        this.mail=mail;this.from=from;this.baseUrl=baseUrl;this.host=host;
    }
    public void checkConfigured() {
        if(from.isBlank()||host.isBlank()||mail.getIfAvailable()==null)
            throw new ApiError.Failure(HttpStatus.SERVICE_UNAVAILABLE,"MAIL_NOT_CONFIGURED","비밀번호 재설정 메일 설정이 필요합니다.");
    }
    public void send(String email,String token) {
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);
        message.setSubject("LIT 비밀번호 재설정");
        // Fragment keeps the token out of the frontend server's ordinary URL request logs.
        message.setText("30분 안에 아래 링크에서 비밀번호를 재설정해주세요.\n"+baseUrl+"#token="+token+"\n요청하지 않았다면 이 메일을 무시해주세요.");
        mail.getObject().send(message);
    }
}
