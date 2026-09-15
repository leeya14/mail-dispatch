package com.yoona.maildispatch;

import static com.yoona.maildispatch.MailModels.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MailService {
  private final MailRepository repo;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MailService(MailRepository repo, TransactionTemplate tx, Clock clock) {
    this.repo = repo;
    this.tx = tx;
    this.clock = clock;
  }

  public Created create(String key, CreateMail r) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}"))
      throw new ApiProblem(400, "INVALID_KEY", "Idempotency-Key는 영문·숫자·밑줄·하이픈 8~128자입니다.");
    String hashedKey = hash(key);
    String fingerprint =
        hash(
            part(r.recipient())
                + part(r.subject())
                + part(r.body())
                + part(r.scheduledAt().toString()));
    var old = repo.replay(hashedKey, fingerprint);
    if (old.isPresent()) return old.get();
    Instant now = clock.instant();
    if (r.scheduledAt().isBefore(now))
      throw new ApiProblem(400, "PAST_SCHEDULE", "예약 시간은 현재 이후여야 합니다.");
    if (r.scheduledAt().isAfter(now.plus(Duration.ofDays(365))))
      throw new ApiProblem(400, "FAR_SCHEDULE", "예약은 365일 이내로 설정하세요.");
    try {
      return tx.execute(s -> new Created(repo.insert(hashedKey, fingerprint, r, now), false));
    } catch (DuplicateKeyException ex) {
      return repo.replay(hashedKey, fingerprint).orElseThrow(() -> ex);
    }
  }

  public Job get(String id) {
    return repo.get(id, false)
        .orElseThrow(() -> new ApiProblem(404, "NOT_FOUND", "발송 요청을 찾을 수 없습니다."));
  }

  public Job cancel(String id) {
    return tx.execute(
        s -> {
          Job j =
              repo.get(id, true)
                  .orElseThrow(() -> new ApiProblem(404, "NOT_FOUND", "발송 요청을 찾을 수 없습니다."));
          if (j.status() == Status.CANCELLED) return j;
          if (j.status() != Status.PENDING && j.status() != Status.RETRY_WAIT)
            throw new ApiProblem(409, "CANNOT_CANCEL", "대기 중인 요청만 취소할 수 있습니다.");
          repo.cancel(id, clock.instant());
          return get(id);
        });
  }

  public List<Attempt> attempts(String id) {
    get(id);
    return repo.attempts(id);
  }

  public JobPage list(Status status, Instant from, Instant to, int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new ApiProblem(400, "INVALID_PAGE", "page는 0~10000, size는 1~100이어야 합니다.");
    if (from != null && to != null && !from.isBefore(to))
      throw new ApiProblem(400, "INVALID_RANGE", "from은 to보다 이전이어야 합니다.");
    List<Job> rows = repo.list(status, from, to, page * size, size + 1);
    return new JobPage(rows.stream().limit(size).toList(), page, size, rows.size() > size);
  }

  private static String part(String s) {
    return s.length() + ":" + s;
  }

  private static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
