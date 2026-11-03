package kr.lit.api;
import kr.lit.auth.SignupEmailDeliveryService;
import jakarta.mail.Session;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class SignupEmailDeliveryTest {
    @SuppressWarnings("unchecked")
    @Test void missingSmtpFailsClosedAndDoesNotReturnCode() {
        ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);
        var service=new SignupEmailDeliveryService(provider,"");
        assertThatThrownBy(()->service.sendVerificationCode("teacher@example.test","123456"))
                .isInstanceOfSatisfying(ApiError.Failure.class,e->assertThat(e.code).isEqualTo("EMAIL_NOT_CONFIGURED"));
    }
    @SuppressWarnings("unchecked")
    @Test void mailFailureIsSanitized() {
        ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);var sender=mock(JavaMailSender.class);when(provider.getIfAvailable()).thenReturn(sender);
        doThrow(new MailAuthenticationException("private SMTP details")).when(sender).send(any(MimeMessage.class));
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var service=new SignupEmailDeliveryService(provider,"noreply@example.test");
        assertThatThrownBy(()->service.sendVerificationCode("teacher@example.test","123456"))
                .isInstanceOfSatisfying(ApiError.Failure.class,e->{assertThat(e.code).isEqualTo("EMAIL_DELIVERY_FAILED");assertThat(e.getMessage()).doesNotContain("private SMTP details","123456");});
    }
    @SuppressWarnings("unchecked")
    @Test void sendVerificationEmailWithHtmlAndPlainText() throws Exception {
        ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);
        var sender=mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        new SignupEmailDeliveryService(provider,"noreply@example.test").sendVerificationCode("teacher@example.test","012345");
        var captured=ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        var message=captured.getValue();
        message.saveChanges();
        // 직렬화 후 다시 읽어 UTF-8과 MIME 대체 본문이 실제 전송 형식에 보존되는지 확인합니다.
        var bytes=new java.io.ByteArrayOutputStream();message.writeTo(bytes);
        var received=new MimeMessage(Session.getInstance(new Properties()),new java.io.ByteArrayInputStream(bytes.toByteArray()));
        assertThat(received.getSubject()).isEqualTo("[LIT] 회원가입 이메일 인증");
        assertThat(received.getAllRecipients()[0].toString()).isEqualTo("teacher@example.test");
        assertThat(received.getFrom()[0].toString()).isEqualTo("noreply@example.test");
        assertThat(getBody(received,"text/plain")).contains("012345","10분");
        assertThat(getBody(received,"text/html")).contains("#007BFF","012345","10분","lang=\"ko\"").doesNotContain("{{code}}");
    }
    private String getBody(Part part,String mimeType) throws Exception {
        if(part.isMimeType(mimeType))return (String)part.getContent();
        if(part.getContent() instanceof Multipart multipart) {
            for(int i=0;i<multipart.getCount();i++) {
                String body=getBody(multipart.getBodyPart(i),mimeType);
                if(body!=null)return body;
            }
        }
        return null;
    }
}
