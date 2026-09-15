package com.yoona.maildispatch;

import static com.yoona.maildispatch.MailModels.*;

import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailGateway implements MailGateway {
  private final JavaMailSender sender;
  private final String from;

  public SmtpMailGateway(JavaMailSender sender, @Value("${app.mail.from}") String from) {
    this.sender = sender;
    this.from = from;
  }

  public void send(Job job) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(job.recipient());
    message.setSubject(job.subject());
    message.setText(job.body());
    try {
      sender.send(message);
    } catch (MailAuthenticationException | MailParseException e) {
      throw new DeliveryFailure(false, "SMTP_CONFIGURATION_OR_ADDRESS");
    } catch (MailException e) {
      boolean permanent = isPermanent(e, 0);
      if (e instanceof MailSendException send)
        for (Exception nested : send.getMessageExceptions()) permanent |= isPermanent(nested, 0);
      throw new DeliveryFailure(!permanent, permanent ? "SMTP_5XX" : "SMTP_TEMPORARY_OR_UNKNOWN");
    }
  }

  private boolean isPermanent(Throwable e, int depth) {
    if (e == null || depth > 10) return false;
    if (e instanceof SMTPAddressFailedException a && a.getReturnCode() >= 500) return true;
    if (e instanceof SMTPSendFailedException a && a.getReturnCode() >= 500) return true;
    if (e instanceof MessagingException m && isPermanent(m.getNextException(), depth + 1))
      return true;
    return isPermanent(e.getCause(), depth + 1);
  }
}
