package kr.lit.api;

import kr.lit.auth.PasswordResetDeliveryService;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.mockito.ArgumentCaptor;
import java.util.Properties;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PasswordResetDeliveryTest {
    @SuppressWarnings("unchecked")
    @Test void sendsResetLinkWithFragmentInHtmlAndPlainText() throws Exception {
        ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);var sender=mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        new PasswordResetDeliveryService(provider,"noreply@example.test","https://frontend.example.test/reset-password").sendResetLink("user@example.test","x".repeat(43));
        var captured=ArgumentCaptor.forClass(MimeMessage.class);verify(sender).send(captured.capture());
        var message=captured.getValue();message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("[LIT] 비밀번호 재설정");
        assertThat(getBody(message,"text/plain")).contains("https://frontend.example.test/reset-password#token="+"x".repeat(43),"15분");
        assertThat(getBody(message,"text/html")).contains("#007BFF","#token="+"x".repeat(43));
        doThrow(new MailAuthenticationException("private SMTP password")).when(sender).send(any(MimeMessage.class));
        assertThatThrownBy(()->new PasswordResetDeliveryService(provider,"noreply@example.test","https://frontend.example.test/reset-password").sendResetLink("user@example.test","x".repeat(43)))
                .isInstanceOfSatisfying(ApiError.Failure.class,e->{assertThat(e.code).isEqualTo("EMAIL_DELIVERY_FAILED");assertThat(e.getMessage()).doesNotContain("private","x".repeat(43));});
    }
    @SuppressWarnings("unchecked")
    @Test void rejectsMissingMailOrUnsafeFrontendUrl() {
        ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
        for(String url:new String[]{"","http://frontend.example.test/reset","https://frontend.example.test/reset?next=x","https://frontend.example.test/reset#x","https://user@frontend.example.test/reset"})
            assertThatThrownBy(()->new PasswordResetDeliveryService(provider,"from@example.test",url).requireConfiguration()).isInstanceOf(ApiError.Failure.class);
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(()->new PasswordResetDeliveryService(provider,"from@example.test","https://frontend.example.test/reset").requireConfiguration()).isInstanceOf(ApiError.Failure.class);
    }
    private String getBody(Part part,String mimeType) throws Exception {
        if(part.isMimeType(mimeType))return (String)part.getContent();
        if(part.getContent() instanceof Multipart multipart)for(int i=0;i<multipart.getCount();i++) {
            String body=getBody(multipart.getBodyPart(i),mimeType);if(body!=null)return body;
        }
        return null;
    }
}
