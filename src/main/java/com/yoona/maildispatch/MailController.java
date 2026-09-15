package com.yoona.maildispatch;

import static com.yoona.maildispatch.MailModels.*;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mails")
public class MailController {
  private final MailService service;

  public MailController(MailService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<Job> create(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateMail r) {
    Created c = service.create(key, r);
    return ResponseEntity.status(c.replayed() ? 200 : 201)
        .location(URI.create("/api/mails/" + c.job().id()))
        .header("Idempotency-Replayed", String.valueOf(c.replayed()))
        .body(c.job());
  }

  @GetMapping
  public JobPage list(
      @RequestParam(required = false) Status status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(status, from, to, page, size);
  }

  @GetMapping("/{id}")
  public Job get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping("/{id}/cancel")
  public Job cancel(@PathVariable String id) {
    return service.cancel(id);
  }

  @GetMapping("/{id}/attempts")
  public List<Attempt> attempts(@PathVariable String id) {
    return service.attempts(id);
  }
}
