package com.yoona.maildispatch;

import com.yoona.maildispatch.MailModels.Job;

public interface MailGateway {
  void send(Job job);

  class DeliveryFailure extends RuntimeException {
    final boolean retryable;
    final String code;

    public DeliveryFailure(boolean retryable, String code) {
      super(code);
      this.retryable = retryable;
      this.code = code;
    }
  }
}
