package kr.lit.api;
import kr.lit.auth.SignupEmailDeliveryService;
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
        doThrow(new MailAuthenticationException("private SMTP details")).when(sender).send(any(SimpleMailMessage.class));
        var service=new SignupEmailDeliveryService(provider,"noreply@example.test");
        assertThatThrownBy(()->service.sendVerificationCode("teacher@example.test","123456"))
                .isInstanceOfSatisfying(ApiError.Failure.class,e->{assertThat(e.code).isEqualTo("EMAIL_DELIVERY_FAILED");assertThat(e.getMessage()).doesNotContain("private SMTP details","123456");});
    }
}
