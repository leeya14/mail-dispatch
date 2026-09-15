package com.yoona.maildispatch;

import static com.yoona.maildispatch.MailModels.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class MailRepository {
  private final JdbcTemplate db;

  public MailRepository(JdbcTemplate db) {
    this.db = db;
  }

  static Timestamp ts(Instant value) {
    return Timestamp.valueOf(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
  }

  static Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toLocalDateTime().toInstant(ZoneOffset.UTC);
  }

  private final RowMapper<Job> mapper =
      (rs, n) ->
          new Job(
              rs.getString("id"),
              rs.getString("recipient"),
              rs.getString("subject"),
              rs.getString("body"),
              instant(rs, "scheduled_at"),
              instant(rs, "next_attempt_at"),
              Status.valueOf(rs.getString("status")),
              rs.getInt("attempt_count"),
              instant(rs, "created_at"),
              instant(rs, "updated_at"));

  public Optional<Job> get(String id, boolean lock) {
    return db
        .query("SELECT * FROM mail_job WHERE id=?" + (lock ? " FOR UPDATE" : ""), mapper, id)
        .stream()
        .findFirst();
  }

  public Optional<Created> replay(String key, String fingerprint) {
    var ids = db.queryForList("SELECT id,fingerprint FROM mail_job WHERE request_key=?", key);
    if (ids.isEmpty()) return Optional.empty();
    if (!fingerprint.equals(ids.get(0).get("fingerprint")))
      throw new ApiProblem(409, "IDEMPOTENCY_CONFLICT", "같은 요청 키에 다른 내용을 사용할 수 없습니다.");
    return Optional.of(new Created(get((String) ids.get(0).get("id"), false).orElseThrow(), true));
  }

  public Job insert(String key, String fingerprint, CreateMail request, Instant now) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " mail_job(id,request_key,fingerprint,recipient,subject,body,scheduled_at,next_attempt_at,status,attempt_count,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,0,?,?)",
        id,
        key,
        fingerprint,
        request.recipient(),
        request.subject(),
        request.body(),
        ts(request.scheduledAt()),
        ts(request.scheduledAt()),
        "PENDING",
        ts(now),
        ts(now));
    return get(id, false).orElseThrow();
  }

  public Optional<Job> nextDue(Instant now) {
    return db
        .query(
            "SELECT * FROM mail_job WHERE status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?"
                + " ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE",
            mapper,
            ts(now))
        .stream()
        .findFirst();
  }

  public Job claim(Job job, Instant now) {
    db.update(
        "UPDATE mail_job SET status='PROCESSING',attempt_count=attempt_count+1,updated_at=? WHERE"
            + " id=?",
        ts(now),
        job.id());
    db.update(
        "INSERT INTO mail_attempt(id,job_id,attempt_no,started_at,outcome) VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        job.id(),
        job.attemptCount() + 1,
        ts(now),
        "PROCESSING");
    return get(job.id(), false).orElseThrow();
  }

  public void complete(Job job, Status status, Instant next, Instant now, String error) {
    int changed =
        db.update(
            "UPDATE mail_job SET status=?,next_attempt_at=?,updated_at=? WHERE id=? AND"
                + " status='PROCESSING' AND attempt_count=?",
            status.name(),
            ts(next),
            ts(now),
            job.id(),
            job.attemptCount());
    if (changed != 1) throw new IllegalStateException("Claim is no longer current");
    db.update(
        "UPDATE mail_attempt SET outcome=?,finished_at=?,error_code=? WHERE job_id=? AND"
            + " attempt_no=?",
        status == Status.SMTP_ACCEPTED ? "SMTP_ACCEPTED" : "FAILED",
        ts(now),
        error,
        job.id(),
        job.attemptCount());
  }

  public void cancel(String id, Instant now) {
    db.update("UPDATE mail_job SET status='CANCELLED',updated_at=? WHERE id=?", ts(now), id);
  }

  public List<Attempt> attempts(String id) {
    return db.query(
        "SELECT * FROM mail_attempt WHERE job_id=? ORDER BY attempt_no",
        (rs, n) ->
            new Attempt(
                rs.getInt("attempt_no"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                rs.getString("outcome"),
                rs.getString("error_code")),
        id);
  }

  public List<Job> list(Status status, Instant from, Instant to, int offset, int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM mail_job WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status=?");
      args.add(status.name());
    }
    if (from != null) {
      sql.append(" AND scheduled_at>=?");
      args.add(ts(from));
    }
    if (to != null) {
      sql.append(" AND scheduled_at<?");
      args.add(ts(to));
    }
    sql.append(" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return db.query(sql.toString(), mapper, args.toArray());
  }
}
