package com.yoona.maildispatch;

import com.yoona.maildispatch.MailModels.ApiError;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiProblem.class)
  public ResponseEntity<ApiError> problem(ApiProblem e) {
    return ResponseEntity.status(e.status).body(new ApiError(e.code, e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MissingRequestHeaderException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<ApiError> bad(Exception e) {
    return ResponseEntity.badRequest()
        .body(new ApiError("INVALID_REQUEST", "필수 항목, 입력 형식, 예약 시간 및 요청 키를 확인하세요."));
  }
}
