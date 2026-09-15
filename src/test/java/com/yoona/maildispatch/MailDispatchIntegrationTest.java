package com.yoona.maildispatch;

import static com.yoona.maildispatch.MailModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Timeout(25)
class MailDispatchIntegrationTest {
  @Autowired MailService service;
  @Autowired MailWorker worker;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean MailGateway gateway;
  @MockitoBean Clock clock;
  final Instant now = Instant.parse("2026-09-15T12:00:00Z");

  @BeforeEach
  void clean() {
    db.update("DELETE FROM mail_attempt");
    db.update("DELETE FROM mail_job");
    when(clock.instant()).thenReturn(now);
  }

  CreateMail request() {
    return new CreateMail("recipient@example.test", "예약 메일 테스트", "본문", now.plusSeconds(60));
  }

  Created create(String key) {
    return service.create(key, request());
  }

  void due() {
    when(clock.instant()).thenReturn(now.plusSeconds(61));
  }

  String body(CreateMail r) throws Exception {
    return json.writeValueAsString(r);
  }

  @Test
  void createsThroughHttpAndReturnsLocation() throws Exception {
    mvc.perform(
            post("/api/mails")
                .header("Idempotency-Key", "request-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(request())))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void repeatedRequestReturnsExistingId() {
    var a = create("request-0001");
    var b = create("request-0001");
    assertThat(b.replayed()).isTrue();
    assertThat(b.job().id()).isEqualTo(a.job().id());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM mail_job", Integer.class)).isEqualTo(1);
  }

  @Test
  void replayAfterScheduledTimeStillWorks() {
    var a = create("request-0001");
    due();
    assertThat(create("request-0001").job().id()).isEqualTo(a.job().id());
  }

  @Test
  void differentPayloadWithSameKeyIsConflict() {
    create("request-0001");
    assertThatThrownBy(
            () ->
                service.create(
                    "request-0001",
                    new CreateMail(
                        "other@example.test", "예약 메일 테스트", "본문", request().scheduledAt())))
        .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status).isEqualTo(409));
  }

  @Test
  void rejectsInvalidEmail() throws Exception {
    mvc.perform(
            post("/api/mails")
                .header("Idempotency-Key", "request-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(new CreateMail("not-email", "제목", "본문", request().scheduledAt()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsHeaderInjection() throws Exception {
    mvc.perform(
            post("/api/mails")
                .header("Idempotency-Key", "request-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        new CreateMail(
                            "a@example.test",
                            "hello\r\nBcc: b@example.test",
                            "본문",
                            request().scheduledAt()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsPastSchedule() {
    assertThatThrownBy(
            () ->
                service.create(
                    "request-0001",
                    new CreateMail("a@example.test", "제목", "본문", now.minusSeconds(1))))
        .isInstanceOfSatisfying(
            ApiProblem.class, e -> assertThat(e.code).isEqualTo("PAST_SCHEDULE"));
  }

  @Test
  void rejectsMissingKey() throws Exception {
    mvc.perform(post("/api/mails").contentType(MediaType.APPLICATION_JSON).content(body(request())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsInvalidKey() {
    assertThatThrownBy(() -> create("x")).isInstanceOf(ApiProblem.class);
  }

  @Test
  void futureJobIsNotSent() {
    create("request-0001");
    assertThat(worker.processOne()).isFalse();
    verifyNoInteractions(gateway);
  }

  @Test
  void dueJobIsAcceptedAndAttemptRecorded() {
    var j = create("request-0001").job();
    due();
    assertThat(worker.processOne()).isTrue();
    assertThat(service.get(j.id()).status()).isEqualTo(Status.SMTP_ACCEPTED);
    assertThat(service.attempts(j.id())).hasSize(1);
    assertThat(service.attempts(j.id()).get(0).outcome()).isEqualTo("SMTP_ACCEPTED");
    verify(gateway, times(1)).send(any());
  }

  @Test
  void cancelledJobNeverSent() {
    var j = create("request-0001").job();
    assertThat(service.cancel(j.id()).status()).isEqualTo(Status.CANCELLED);
    due();
    assertThat(worker.processOne()).isFalse();
    verifyNoInteractions(gateway);
  }

  @Test
  void cancellationIsIdempotent() {
    var j = create("request-0001").job();
    service.cancel(j.id());
    assertThat(service.cancel(j.id()).status()).isEqualTo(Status.CANCELLED);
  }

  @Test
  void cannotCancelAcceptedJob() {
    var j = create("request-0001").job();
    due();
    worker.processOne();
    assertThatThrownBy(() -> service.cancel(j.id()))
        .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status).isEqualTo(409));
  }

  @Test
  void temporaryFailureRetriesAfterDelayAndSucceeds() {
    var j = create("request-0001").job();
    doThrow(new MailGateway.DeliveryFailure(true, "SMTP_TEMPORARY_OR_UNKNOWN"))
        .doNothing()
        .when(gateway)
        .send(any());
    due();
    worker.processOne();
    assertThat(service.get(j.id()).status()).isEqualTo(Status.RETRY_WAIT);
    assertThat(service.get(j.id()).nextAttemptAt()).isEqualTo(now.plusSeconds(91));
    assertThat(worker.processOne()).isFalse();
    when(clock.instant()).thenReturn(now.plusSeconds(92));
    worker.processOne();
    assertThat(service.get(j.id()).status()).isEqualTo(Status.SMTP_ACCEPTED);
    assertThat(service.attempts(j.id())).hasSize(2);
  }

  @Test
  void retryExhaustionStopsAfterThreeAttempts() {
    var j = create("request-0001").job();
    doThrow(new MailGateway.DeliveryFailure(true, "TEMPORARY")).when(gateway).send(any());
    due();
    worker.processOne();
    when(clock.instant()).thenReturn(now.plusSeconds(92));
    worker.processOne();
    assertThat(service.get(j.id()).nextAttemptAt()).isEqualTo(now.plusSeconds(152));
    when(clock.instant()).thenReturn(now.plusSeconds(153));
    worker.processOne();
    assertThat(service.get(j.id()).status()).isEqualTo(Status.FAILED);
    assertThat(service.get(j.id()).attemptCount()).isEqualTo(3);
    assertThat(worker.processOne()).isFalse();
  }

  @Test
  void permanentFailureDoesNotRetry() {
    var j = create("request-0001").job();
    doThrow(new MailGateway.DeliveryFailure(false, "SMTP_5XX")).when(gateway).send(any());
    due();
    worker.processOne();
    assertThat(service.get(j.id()).status()).isEqualTo(Status.FAILED);
    assertThat(worker.processOne()).isFalse();
  }

  @Test
  void cancelDuringRetryWaitStopsFurtherAttempts() {
    var j = create("request-0001").job();
    doThrow(new MailGateway.DeliveryFailure(true, "TEMPORARY")).when(gateway).send(any());
    due();
    worker.processOne();
    service.cancel(j.id());
    when(clock.instant()).thenReturn(now.plusSeconds(1000));
    assertThat(worker.processOne()).isFalse();
    verify(gateway, times(1)).send(any());
  }

  @Test
  void statusAndTimeFilteringAndPagination() {
    var a = create("request-0001");
    create("request-0002");
    create("request-0003");
    service.cancel(a.job().id());
    var page = service.list(Status.PENDING, now, now.plusSeconds(120), 0, 1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.hasNext()).isTrue();
    assertThat(service.list(Status.PENDING, now, now.plusSeconds(120), 1, 1).hasNext()).isFalse();
    assertThat(service.list(null, now.plusSeconds(120), null, 0, 20).items()).isEmpty();
  }

  @Test
  void invalidPaginationAndRangeReturn400() throws Exception {
    mvc.perform(get("/api/mails?page=-1")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/mails?size=101")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/mails?from=2026-10-01T00:00:00Z&to=2026-09-01T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownJobReturns404() throws Exception {
    mvc.perform(get("/api/mails/missing")).andExpect(status().isNotFound());
  }

  @Test
  void concurrentDuplicateRequestsCreateOneJob() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return create("request-0001").job().id();
                }));
      start.countDown();
      Set<String> ids = new HashSet<>();
      for (var f : results) ids.add(f.get(15, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
      assertThat(db.queryForObject("SELECT COUNT(*) FROM mail_job", Integer.class)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void twoWorkersDoNotSendSameJobTwice() throws Exception {
    create("request-0001");
    due();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                start.await();
                return worker.processOne();
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return worker.processOne();
              });
      start.countDown();
      assertThat((a.get(15, TimeUnit.SECONDS) ? 1 : 0) + (b.get(15, TimeUnit.SECONDS) ? 1 : 0))
          .isEqualTo(1);
      verify(gateway, times(1)).send(any());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cannotCancelWhileSmtpIsInProgress() throws Exception {
    var j = create("request-0001").job();
    due();
    CountDownLatch sending = new CountDownLatch(1), finish = new CountDownLatch(1);
    doAnswer(
            i -> {
              sending.countDown();
              if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
              return null;
            })
        .when(gateway)
        .send(any());
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      var result = pool.submit(() -> worker.processOne());
      assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> service.cancel(j.id()))
          .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status).isEqualTo(409));
      finish.countDown();
      assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      finish.countDown();
      pool.shutdownNow();
    }
  }
}
