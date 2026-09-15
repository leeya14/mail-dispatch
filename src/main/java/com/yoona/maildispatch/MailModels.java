package com.yoona.maildispatch;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class MailModels {
  private MailModels() {}

  public enum Status {
    PENDING,
    PROCESSING,
    SMTP_ACCEPTED,
    RETRY_WAIT,
    FAILED,
    CANCELLED
  }

  public record CreateMail(
      @NotBlank @Email @Size(max = 254) @Pattern(regexp = "[^\\r\\n]+") String recipient,
      @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\r\\n]+") String subject,
      @NotBlank @Size(max = 20000) String body,
      @NotNull Instant scheduledAt) {}

  public record Job(
      String id,
      String recipient,
      String subject,
      String body,
      Instant scheduledAt,
      Instant nextAttemptAt,
      Status status,
      int attemptCount,
      Instant createdAt,
      Instant updatedAt) {}

  public record Attempt(
      int attemptNo, Instant startedAt, Instant finishedAt, String outcome, String errorCode) {}

  public record Created(Job job, boolean replayed) {}

  public record JobPage(List<Job> items, int page, int size, boolean hasNext) {}

  public record ApiError(String code, String message) {}
}
