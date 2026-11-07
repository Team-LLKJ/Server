package kr.lit.auth;

import jakarta.mail.MessagingException;
import kr.lit.api.ApiError;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Service
public class PasswordResetDeliveryService {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from,resetUrl;
    public PasswordResetDeliveryService(ObjectProvider<JavaMailSender> senders,@Value("${lit.auth.mail-from:}") String from,
                                        @Value("${lit.auth.password-reset-url:}") String resetUrl) {
        this.senders=senders;this.from=from;this.resetUrl=resetUrl;
    }
    public void requireConfiguration() {
        try {
            var uri=URI.create(resetUrl);
            if(senders.getIfAvailable()==null||from.isBlank()||!"https".equals(uri.getScheme())||uri.getHost()==null
                    ||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException();
        }catch(IllegalArgumentException ex) {
            throw new ApiError.Failure(HttpStatus.SERVICE_UNAVAILABLE,"PASSWORD_RESET_NOT_CONFIGURED","비밀번호 재설정 메일 설정이 준비되지 않았습니다.");
        }
    }
    public void sendResetLink(String email,String token) {
        requireConfiguration();
        // 프런트 문서 요청·접근 로그에 토큰이 실리지 않도록 fragment를 사용합니다.
        String link=resetUrl+"#token="+token;
        try {
            var sender=senders.getIfAvailable();var message=sender.createMimeMessage();
            var helper=new MimeMessageHelper(message,true,StandardCharsets.UTF_8.name());
            helper.setFrom(from);helper.setTo(email);helper.setSubject("[LIT] 비밀번호 재설정");
            String plain="아래 링크에서 LIT 비밀번호를 재설정해주세요.\n"+link+"\n15분 동안 한 번만 사용할 수 있습니다. 요청하지 않았다면 무시해주세요.";
            String html="<html lang=\"ko\"><body style=\"font-family:Arial,sans-serif;color:#172B4D\"><h1 style=\"color:#007BFF\">LIT 비밀번호 재설정</h1>"
                    +"<p>아래 버튼에서 새 비밀번호를 설정해주세요.</p><p><a href=\""+HtmlUtils.htmlEscape(link)+"\">비밀번호 재설정</a></p>"
                    +"<p>링크는 15분 동안 한 번만 사용할 수 있습니다. 다른 사람에게 공유하지 마세요.</p><p>요청하지 않았다면 이 메일을 무시해주세요.</p></body></html>";
            helper.setText(plain,html);sender.send(message);
        }catch(MailException|MessagingException ex) {
            throw new ApiError.Failure(HttpStatus.BAD_GATEWAY,"EMAIL_DELIVERY_FAILED","재설정 메일을 발송하지 못했습니다. 잠시 후 다시 시도해주세요.");
        }
    }
}
