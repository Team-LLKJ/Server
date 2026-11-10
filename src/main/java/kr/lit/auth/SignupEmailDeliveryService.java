package kr.lit.auth;
import kr.lit.api.ApiError;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import jakarta.mail.MessagingException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import java.nio.charset.StandardCharsets;
@Service
public class SignupEmailDeliveryService {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    public SignupEmailDeliveryService(ObjectProvider<JavaMailSender> senders,@Value("${lit.auth.mail-from:}") String from) {this.senders=senders;this.from=from;}
    public void sendVerificationCode(String email,String code) {
        var sender=senders.getIfAvailable();
        if(sender==null||from.isBlank())throw new ApiError.Failure(HttpStatus.SERVICE_UNAVAILABLE,"EMAIL_NOT_CONFIGURED","인증메일 발송 설정이 준비되지 않았습니다.");
        try {
            var message=sender.createMimeMessage();
            var helper=new MimeMessageHelper(message,true,StandardCharsets.UTF_8.name());
            helper.setFrom(from);helper.setTo(email);helper.setSubject("[LIT] 회원가입 이메일 인증");
            helper.setText("LIT 이메일 인증번호: "+code+"\n10분 안에 입력해주세요. 요청하지 않았다면 이 메일을 무시해주세요.",createVerificationHtml(code));
            sender.send(message);
        }catch(MailException|MessagingException ex) {throw new ApiError.Failure(HttpStatus.BAD_GATEWAY,"EMAIL_DELIVERY_FAILED","인증메일을 발송하지 못했습니다. 잠시 후 다시 시도해주세요.");}
    }

    private String createVerificationHtml(String code) {
        return """
                <!doctype html>
                <html lang="ko">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>LIT 이메일 인증</title></head>
                <body style="margin:0;padding:0;background-color:#F3F7FC;color:#172B4D;font-family:Arial,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;">
                  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#F3F7FC;">
                    <tr><td align="center" style="padding:40px 16px;">
                      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background-color:#FFFFFF;border:1px solid #E3EAF3;border-top:6px solid #007BFF;border-radius:16px;">
                        <tr><td style="padding:36px 28px 24px;">
                          <div style="color:#007BFF;font-size:30px;font-weight:800;letter-spacing:2px;">LIT</div>
                          <h1 style="margin:28px 0 12px;font-size:24px;line-height:1.5;">이메일 주소를 인증해 주세요</h1>
                          <p style="margin:0;font-size:15px;line-height:1.8;color:#52627A;">LIT에 오신 것을 환영합니다.<br>아래 인증번호를 회원가입 화면에 입력해 주세요.</p>
                        </td></tr>
                        <tr><td style="padding:0 28px;">
                          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#EDF5FF;border:1px solid #CFE5FF;border-radius:12px;">
                            <tr><td align="center" style="padding:24px 12px;">
                              <p style="margin:0 0 10px;color:#52627A;font-size:13px;">이메일 인증번호</p>
                              <div style="color:#007BFF;font-family:Consolas,Menlo,monospace;font-size:36px;font-weight:700;letter-spacing:6px;line-height:1.5;">{{code}}</div>
                            </td></tr>
                          </table>
                        </td></tr>
                        <tr><td style="padding:24px 28px 36px;">
                          <p style="margin:0;font-size:14px;line-height:1.8;color:#52627A;">인증번호는 <strong style="color:#172B4D;">10분 동안</strong> 유효합니다.<br>안전한 계정 보호를 위해 다른 사람에게 공유하지 마세요.</p>
                          <p style="margin:24px 0 0;padding-top:20px;border-top:1px solid #E3EAF3;font-size:12px;line-height:1.8;color:#6B778C;">본인이 요청하지 않았다면 이 메일을 무시해 주세요.<br>이 메일은 LIT 회원가입 이메일 인증을 위해 자동 발송되었습니다.</p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.replace("{{code}}",HtmlUtils.htmlEscape(code));
    }
}
