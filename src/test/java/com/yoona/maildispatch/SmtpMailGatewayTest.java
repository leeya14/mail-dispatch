package com.yoona.maildispatch;

import static com.yoona.maildispatch.MailModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.mail.internet.InternetAddress;
import java.time.Instant;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailGatewayTest {
  Job job() {
    return new Job(
        "id",
        "a@example.test",
        "제목",
        "본문",
        Instant.EPOCH,
        Instant.EPOCH,
        Status.PROCESSING,
        1,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  @Test
  void authenticationFailureIsPermanent() {
    JavaMailSender sender = mock(JavaMailSender.class);
    doThrow(new MailAuthenticationException("secret must not leak"))
        .when(sender)
        .send(any(SimpleMailMessage.class));
    assertThatThrownBy(() -> new SmtpMailGateway(sender, "sender@example.test").send(job()))
        .isInstanceOfSatisfying(
            MailGateway.DeliveryFailure.class,
            e -> {
              assertThat(e.retryable).isFalse();
              assertThat(e.getMessage()).doesNotContain("secret");
            });
  }

  @Test
  void connectionFailureIsRetryable() {
    JavaMailSender sender = mock(JavaMailSender.class);
    doThrow(new MailSendException("connection refused"))
        .when(sender)
        .send(any(SimpleMailMessage.class));
    assertThatThrownBy(() -> new SmtpMailGateway(sender, "sender@example.test").send(job()))
        .isInstanceOfSatisfying(
            MailGateway.DeliveryFailure.class, e -> assertThat(e.retryable).isTrue());
  }

  @Test
  void smtp550IsPermanent() throws Exception {
    JavaMailSender sender = mock(JavaMailSender.class);
    var failure =
        new SMTPAddressFailedException(
            new InternetAddress("a@example.test"), "RCPT TO", 550, "unknown mailbox");
    doThrow(new MailSendException("rejected", failure))
        .when(sender)
        .send(any(SimpleMailMessage.class));
    assertThatThrownBy(() -> new SmtpMailGateway(sender, "sender@example.test").send(job()))
        .isInstanceOfSatisfying(
            MailGateway.DeliveryFailure.class,
            e -> {
              assertThat(e.retryable).isFalse();
              assertThat(e.code).isEqualTo("SMTP_5XX");
            });
  }
}
