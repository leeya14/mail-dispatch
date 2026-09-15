package com.yoona.maildispatch;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class MailScheduler {
  private static final Logger log = LoggerFactory.getLogger(MailScheduler.class);
  private final MailWorker worker;

  public MailScheduler(MailWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${app.worker.delay-ms}")
  public void tick() {
    try {
      for (int i = 0; i < 20 && worker.processOne(); i++) {}
    } catch (RuntimeException e) {
      log.error("Worker stopped this tick; inspect PROCESSING jobs", e);
    }
  }
}
