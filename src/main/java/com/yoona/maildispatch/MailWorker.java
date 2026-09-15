package com.yoona.maildispatch;

import static com.yoona.maildispatch.MailModels.*;

import java.time.*;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MailWorker {
  private final MailRepository repo;
  private final TransactionTemplate tx;
  private final MailGateway gateway;
  private final Clock clock;
  private final long baseSeconds;
  private final int maxAttempts;

  public MailWorker(
      MailRepository repo,
      TransactionTemplate tx,
      MailGateway gateway,
      Clock clock,
      @Value("${app.retry.base-seconds}") long baseSeconds,
      @Value("${app.retry.max-attempts}") int maxAttempts) {
    if (baseSeconds < 1 || maxAttempts < 1 || maxAttempts > 10)
      throw new IllegalArgumentException("Invalid retry configuration");
    this.repo = repo;
    this.tx = tx;
    this.gateway = gateway;
    this.clock = clock;
    this.baseSeconds = baseSeconds;
    this.maxAttempts = maxAttempts;
  }

  public boolean processOne() {
    Optional<Job> claimed =
        tx.execute(s -> repo.nextDue(clock.instant()).map(j -> repo.claim(j, clock.instant())));
    if (claimed == null || claimed.isEmpty()) return false;
    Job j = claimed.get();
    Status result = Status.SMTP_ACCEPTED;
    String error = null;
    Instant next = j.nextAttemptAt();
    // SMTP I/O deliberately happens outside the database transaction.
    try {
      gateway.send(j);
    } catch (MailGateway.DeliveryFailure e) {
      error = e.code;
      if (e.retryable && j.attemptCount() < maxAttempts) {
        result = Status.RETRY_WAIT;
        next = clock.instant().plusSeconds(baseSeconds * (1L << (j.attemptCount() - 1)));
      } else result = Status.FAILED;
    } catch (RuntimeException e) {
      result = Status.FAILED;
      error = "UNEXPECTED_DELIVERY_ERROR";
    }
    Status finalResult = result;
    String finalError = error;
    Instant finalNext = next;
    tx.executeWithoutResult(
        s -> repo.complete(j, finalResult, finalNext, clock.instant(), finalError));
    return true;
  }
}
