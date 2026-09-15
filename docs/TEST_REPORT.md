# 실제 검증 결과

확인일: 2026-09-15

- Java 17.0.20 / Maven 3.9.11 / Spring Boot 3.5.16
- 자동 테스트: 27개 실행, 실패 0, 오류 0, 건너뜀 0
- 테스트 DB: H2 (MySQL 호환 모드)
- 실제 로컬 HTTP + SMTP 연동: 9개 확인 항목 통과
- 외부 이메일 발송 없음
- MySQL 서버·Docker Compose·Windows 실행·브라우저 UI 상호작용·다중 인스턴스·실서비스 부하 검증은 미실시

검증 실행: Codex 작업 환경에서 자동 수행.

## 자동 테스트 목록

| 분류 | 테스트 | 결과 |
|---|---|---|
| MailDispatchIntegrationTest | dueJobIsAcceptedAndAttemptRecorded | PASS |
| MailDispatchIntegrationTest | differentPayloadWithSameKeyIsConflict | PASS |
| MailDispatchIntegrationTest | retryExhaustionStopsAfterThreeAttempts | PASS |
| MailDispatchIntegrationTest | permanentFailureDoesNotRetry | PASS |
| MailDispatchIntegrationTest | invalidPaginationAndRangeReturn400 | PASS |
| MailDispatchIntegrationTest | rejectsHeaderInjection | PASS |
| MailDispatchIntegrationTest | unknownJobReturns404 | PASS |
| MailDispatchIntegrationTest | repeatedRequestReturnsExistingId | PASS |
| MailDispatchIntegrationTest | cancelledJobNeverSent | PASS |
| MailDispatchIntegrationTest | rejectsInvalidKey | PASS |
| MailDispatchIntegrationTest | rejectsInvalidEmail | PASS |
| MailDispatchIntegrationTest | statusAndTimeFilteringAndPagination | PASS |
| MailDispatchIntegrationTest | replayAfterScheduledTimeStillWorks | PASS |
| MailDispatchIntegrationTest | cannotCancelAcceptedJob | PASS |
| MailDispatchIntegrationTest | futureJobIsNotSent | PASS |
| MailDispatchIntegrationTest | rejectsMissingKey | PASS |
| MailDispatchIntegrationTest | cancellationIsIdempotent | PASS |
| MailDispatchIntegrationTest | cannotCancelWhileSmtpIsInProgress | PASS |
| MailDispatchIntegrationTest | rejectsPastSchedule | PASS |
| MailDispatchIntegrationTest | temporaryFailureRetriesAfterDelayAndSucceeds | PASS |
| MailDispatchIntegrationTest | concurrentDuplicateRequestsCreateOneJob | PASS |
| MailDispatchIntegrationTest | cancelDuringRetryWaitStopsFurtherAttempts | PASS |
| MailDispatchIntegrationTest | twoWorkersDoNotSendSameJobTwice | PASS |
| MailDispatchIntegrationTest | createsThroughHttpAndReturnsLocation | PASS |
| SmtpMailGatewayTest | smtp550IsPermanent | PASS |
| SmtpMailGatewayTest | connectionFailureIsRetryable | PASS |
| SmtpMailGatewayTest | authenticationFailureIsPermanent | PASS |

## 실제 HTTP + SMTP 확인 항목

- HTTP create 201
- Duplicate replay 200; same id
- Changed payload 409
- SMTP 451 triggers retry
- Second SMTP attempt accepted
- Attempt history FAILED -> SMTP_ACCEPTED
- Exactly one message captured; Korean subject/body verified
- Future request cancellation
- Invalid email 400

Mockito는 테스트 환경의 JVM self-attach 제약을 피하기 위해 subclass mock maker를 사용합니다. 실제 SMTP 전송은 별도 E2E 테스트에서 JavaMailSender와 로컬 SMTP 서버로 확인했습니다.

프런트엔드 JavaScript 구문 검사 통과. 브라우저 실행 파일 설치가 시간 초과되어 브라우저 UI 상호작용 검증은 미실시했습니다. HTTP API 및 실제 SMTP 연동 검증과는 별개입니다.
