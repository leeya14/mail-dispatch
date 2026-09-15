# Mail Dispatch

## 실행해 보기

[실행용 ZIP 다운로드 및 실행 안내](https://github.com/leeya14/mail-dispatch/releases/tag/v1.0.0)

Java 17 이상과 Python 3가 설치된 환경에서 실행할 수 있습니다.
첨부된 `mail-dispatch-demo.zip`을 압축 해제한 뒤
`start-demo.bat`을 실행하고 http://127.0.0.1:8081에 접속하세요.

외부 이메일 발송 없이 로컬 수신함에서 결과를 확인할 수 있습니다.

**메일 예약 발송·취소·실패 재시도를 처리하는 Spring Boot 백엔드 포트폴리오 프로젝트.**

제작 방식: 생성형 AI(Codex) 협업. 코드 생성·자동 테스트·로컬 연동 검증에 AI 도구를 활용했습니다.
로컬 테스트 환경을 기준으로 제작했습니다.

## 빠른 실행

배포 ZIP에는 실행용 JAR가 `run/`에 포함됩니다. Java 17 이상과 Python 3가 필요합니다.

- Windows: `start-demo.bat` 실행 (Java와 Python이 PATH에 등록되어 있어야 합니다).
- macOS/Linux: `bash start-demo.sh` 실행.
- 예약 화면: http://127.0.0.1:8081
- 테스트 수신함: http://127.0.0.1:8025

화면에서 1분 뒤로 예약하고, 시간이 지난 뒤 **새로고침**하면 처리 결과를 볼 수 있습니다.
기본 구성은 내장 H2 파일 DB와 로컬 SMTP 수신함입니다. 외부 이메일로 전달하지 않습니다.
앱의 요청·시도 이력은 `data/`에 유지되고, Python 테스트 수신함은 종료 시 비워집니다.
Windows 실행 시 앱 종료 후 별도 SMTP 창도 닫으세요.

## 구현 범위

- 제목·본문·수신자·예약 시간 검증 및 예약 등록
- 요청 키의 DB UNIQUE 제약을 통한 동시 중복 요청 처리
- 같은 키·같은 내용 재요청은 기존 요청 반환, 다른 내용은 HTTP 409
- 대기/재시도 대기 요청 취소, 발송 처리 중/완료 요청 취소 거부
- DB 트랜잭션과 행 잠금으로 발송 작업을 선점한 뒤, 트랜잭션 밖에서 SMTP 호출
- SMTP 일시 오류는 최대 총 3회 시도, 기본 30초·60초 대기
- 인증/파싱 오류 및 SMTP 5xx는 재시도 없이 실패 처리
- 시도 시작·종료 시간과 오류 코드 기록
- 상태·예약 시간 조건 조회, 페이지 조회
- 한국어 데모 화면, API 명세, 테스트 코드, 로컬 SMTP 수신함

## 기술 구성

Java 17 / Spring Boot 3.5.16 / Spring Web / Spring JDBC / Bean Validation / JavaMailSender / H2 / JUnit 5 / Maven.
MySQL JDBC 드라이버와 선택적 Compose 설정도 포함합니다. 이번 검증은 H2에서 수행했으며 MySQL 실환경 테스트는 별도입니다.

## 상태 의미

| 상태 | 의미 | 취소 가능 |
|---|---|---|
| PENDING | 예약 시각 대기 | 가능 |
| PROCESSING | 작업 선점, SMTP 처리 중 | 불가 |
| SMTP_ACCEPTED | SMTP 서버가 요청을 접수 | 불가 |
| RETRY_WAIT | 일시 오류 후 다음 시도 대기 | 가능 |
| FAILED | 영구 오류 또는 시도 횟수 소진 | 불가 |
| CANCELLED | 사용자 취소 | 이미 취소된 결과 반환 |

SMTP_ACCEPTED는 최종 수신함 배달 완료를 뜻하지 않습니다.

## 빌드·검증

```bash
mvn clean verify
python3 tools/verify_e2e.py
```

두 번째 명령은 패키징된 JAR와 로컬 SMTP 서버를 임시 포트로 실행합니다.
첫 SMTP 시도에 451을 응답하게 한 뒤 재시도, 실제 메일 수신, 중복 요청, 취소를 검증합니다.
인터넷 이메일 발송은 발생하지 않습니다. 결과는 `docs/e2e-result.json`에 저장됩니다.
실행 결과 및 범위는 `docs/TEST_REPORT.md` 참조.

## 설계 포인트

1. **멱등 요청:** 클라이언트의 Idempotency-Key를 SHA-256으로 저장하고 DB UNIQUE 제약으로 경쟁 요청을 조정합니다. 문자열을 길이+내용 형식으로 결합한 fingerprint로 내용 충돌을 검사합니다. 키는 대소문자를 구분합니다.
2. **선점과 취소:** 같은 요청 행에 대해 트랜잭션 내 SELECT FOR UPDATE를 사용합니다. 처리 중으로 바뀐 요청은 취소할 수 없습니다.
3. **SMTP와 DB 분리:** 느린 SMTP 통신 동안 DB 잠금을 유지하지 않습니다. 처리 시도는 통신 전에 기록하고 종료 후 결과를 저장합니다.
4. **재시도:** 일시 오류와 영구 오류를 구분합니다. SMTP 전송 결과가 불확실한 연결 오류는 제한적으로 재시도하며 중복 전송 가능성을 문서화합니다.
5. **시간:** API는 UTC 또는 오프셋을 포함한 ISO 8601을 받습니다. DB는 UTC 의미의 TIMESTAMP로 저장하고 UI는 기기 시간으로 표시합니다. 조회 범위는 from 포함, to 미포함입니다.

## 한계와 운영 전 필요한 작업

- 단일 애플리케이션 인스턴스를 기준으로 한 로컬 데모입니다. 인증·권한·사용자별 데이터 분리·발송 제한은 구현하지 않았습니다. 기본 서버 주소는 127.0.0.1입니다.
- 외부 SMTP 설정으로 변경하면 실제 발송이 가능하므로 데모에서는 기본 로컬 SMTP를 유지하세요.
- SMTP 접수와 DB 결과 저장은 하나의 원자적 트랜잭션이 아닙니다. 접수 뒤 연결이 끊기면 재시도로 중복 전달될 수 있습니다. exactly-once 발송을 보장하지 않습니다.
- PROCESSING 상태에서 서버가 종료되면 해당 요청을 자동 재발송하지 않습니다. 작업 중단/결과 불확실 상태를 시도 이력에 남기고 수신 여부 확인 후 수동 조치해야 합니다.
- H2 동시성 테스트는 통과 범위만 증명합니다. 다중 서버·대량 발송·MySQL 잠금 동작·성능은 별도 검증이 필요합니다.
- DB 마이그레이션 도구와 성능 최적화는 미구현입니다. schema.sql은 최초 테이블 생성용이며 기존 스키마 변경을 자동 반영하지 않습니다.

## 선택: MySQL + Mailpit

Python SMTP 서버가 실행 중이라면 먼저 종료하세요. `.env.example`을 `.env`로 복사하고 로컬 비밀번호를 설정한 뒤 `docker compose up -d`를 실행합니다.
앱 실행 시 환경변수 DB_URL=`jdbc:mysql://127.0.0.1:3307/mail_dispatch?connectionTimeZone=UTC`, DB_USERNAME=`mail_demo`, DB_PASSWORD를 설정합니다.
이 Compose 경로는 제공된 설정이며 이번 환경에서는 Docker 실행 검증을 하지 않았습니다.

## 파일 안내

- `docs/API.md`: 요청·응답 예시
- `docs/DESIGN.md`: DB 관계·오류 처리 기준
- `docs/TEST_REPORT.md`: 실제 검증 결과
- `docs/AI_COLLABORATION.md`: 제작·검증 과정과 기여 범위
- `tools/demo_smtp.py`: 로컬 SMTP 수신함
- `tools/verify_e2e.py`: HTTP + 실제 SMTP 자동 연동 검증
