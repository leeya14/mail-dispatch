# API 명세

Base URL: `http://127.0.0.1:8081`

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | /api/mails | 예약 생성; Idempotency-Key 필수 |
| GET | /api/mails | 상태·시간 필터와 페이지 조회 |
| GET | /api/mails/{id} | 상세 조회 |
| POST | /api/mails/{id}/cancel | 예약 취소 |
| GET | /api/mails/{id}/attempts | 발송 시도 이력 |

## 예약 등록

```http
POST /api/mails
Content-Type: application/json
Idempotency-Key: request-demo-0001
```

```json
{
  "recipient": "recipient@example.test",
  "subject": "예약 메일 테스트",
  "body": "안녕하세요. 테스트 메일입니다.",
  "scheduledAt": "2026-09-20T10:00:00+09:00"
}
```

예약 시각은 실행 당시 현재 이후·365일 이내 값으로 변경하세요. 시각대 오프셋 또는 Z가 필수입니다.
수신자는 하나만 받습니다. 제목은 200자, 본문은 20,000자까지이며 빈 문자열은 허용하지 않습니다.
요청 키는 영문·숫자·밑줄·하이픈 8~128자입니다.

- 최초 생성: 201, `Location` 헤더, `Idempotency-Replayed: false`
- 동일 키·동일 입력: 200, 기존 요청, `Idempotency-Replayed: true`
- 동일 키·다른 입력: 409, `IDEMPOTENCY_CONFLICT`
- 잘못된 입력·키·과거 예약: 400

응답에는 id, recipient, subject, body, scheduledAt, nextAttemptAt, status, attemptCount, createdAt, updatedAt이 포함됩니다.

## 조건 조회

`GET /api/mails?status=RETRY_WAIT&from=2026-09-01T00:00:00Z&to=2026-10-01T00:00:00Z&page=0&size=20`

시간 조건은 **scheduledAt** 기준이며 from 포함·to 미포함입니다. 정렬은 생성 시간·ID 내림차순입니다.
page: 0~10000 / size: 1~100. 응답은 `{items, page, size, hasNext}`이며 전체 건수 쿼리는 실행하지 않습니다.
존재하지 않는 상세·이력·취소 요청은 404입니다.

## 취소

PENDING/RETRY_WAIT에서만 CANCELLED로 변경합니다. 이미 CANCELLED면 같은 상태로 200을 반환합니다.
PROCESSING/SMTP_ACCEPTED/FAILED에서는 409 `CANNOT_CANCEL`입니다.

## 시도 이력

attemptNo, startedAt, finishedAt, outcome, errorCode를 반환합니다.
outcome은 PROCESSING / SMTP_ACCEPTED / FAILED입니다. 진행 중인 시도는 finishedAt이 null입니다.
오류 코드만 저장하며 SMTP 자격증명이나 서버의 상세 오류 본문을 API로 노출하지 않습니다.
