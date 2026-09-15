# 설계 범위

## DB

mail_job 1 : N mail_attempt

| 테이블 | 주요 컬럼 | 제약 |
|---|---|---|
| mail_job | id, request_key, fingerprint, recipient, subject, body, scheduled_at, next_attempt_at, status, attempt_count | id PK, request_key UNIQUE |
| mail_attempt | id, job_id, attempt_no, started_at, finished_at, outcome, error_code | id PK, job_id FK, (job_id, attempt_no) UNIQUE |

## 경쟁 상황

등록은 UNIQUE 제약 위반이 발생한 트랜잭션을 종료한 뒤 기존 레코드를 조회합니다. 동일 입력이면 기존 ID를 반환하고 내용이 달라지면 충돌로 처리합니다.
작업 선점과 예약 취소는 같은 요청 행을 잠급니다. 선점 트랜잭션이 먼저 처리되면 취소는 거부되고, 취소가 먼저 처리되면 작업 대상에서 제외됩니다.
SMTP 통신에는 DB 트랜잭션을 열어두지 않습니다. 작업 선점 시 PROCESSING 이력을 먼저 커밋합니다.

## 실패 분류

| 원인 | 처리 |
|---|---|
| SMTP 인증·주소 파싱 오류 | 즉시 FAILED |
| SMTP 5xx 응답 | 즉시 FAILED |
| SMTP 4xx·연결 오류·결과 미확정 오류 | 제한 재시도 |
| 예상하지 못한 코드 실행 오류 | FAILED, UNEXPECTED_DELIVERY_ERROR |
| 선점 후 프로세스 종료·결과 DB 저장 실패 | PROCESSING 유지; 자동 재발송하지 않음 |

기본 총 시도 횟수는 3회입니다. 첫 실패 후 30초, 두 번째 실패 후 60초 대기합니다.
외부 SMTP 처리와 DB 커밋의 원자성은 보장되지 않으므로, 일부 연결 오류의 재시도는 중복 메일을 만들 수 있습니다.
