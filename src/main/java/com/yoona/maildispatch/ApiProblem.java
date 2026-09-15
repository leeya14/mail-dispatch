package com.yoona.maildispatch;

public class ApiProblem extends RuntimeException {
  final int status;
  final String code;

  public ApiProblem(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
