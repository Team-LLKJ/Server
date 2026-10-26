# LIT 평가 참여 API

평가 참여, 문항별 답안, AI 대화, 학습 과정 기록 및 최종 제출을 구현한 Spring Boot 3.5.16 / Java 17+ 프로젝트입니다.
Maven Wrapper가 포함되어 Maven을 별도로 설치할 필요가 없습니다. 최초 실행 시 인터넷이 필요합니다.

## 실행

```sh
cd /Users/dgsw10/Desktop/LIT
export LIT_TEACHER_KEY='replace-with-a-long-random-secret'
export LIT_JOIN_BASE_URL='http://localhost:3000/join'
./mvnw spring-boot:run
```

기본 API 주소는 `http://localhost:8080`입니다. Java 17 이상을 사용하세요.
교사 키를 설정하지 않으면 교사 API가 503을 반환합니다. 키는 서버/교사 측에서만 보관하세요.
교사 계정별 로그인/소유권 요구사항이 제공되지 않아, 하나의 서버 교사 키를 사용합니다.
실제 운영 전에는 사용자 인증 및 교사별 평가 접근 제어를 연동해야 합니다.

```sh
./mvnw test
./mvnw package
java -jar target/lit-api-0.0.1-SNAPSHOT.jar
```

기본 데이터는 `data/lit.mv.db`에 저장되어 재시작 후에도 유지됩니다.
DB 접속 설정은 `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` 환경변수로 변경할 수 있습니다.
현재 스키마와 검증은 H2 기준입니다. 다른 DB를 사용하려면 드라이버와 스키마를 맞춰야 합니다.

## API

교사 API는 `Authorization: Bearer <LIT_TEACHER_KEY>` 헤더가 필요합니다.
학생 API의 상태/하트비트/나가기는 `X-Participant-Token: <participantToken>` 헤더가 필요합니다.
모든 요청/응답 JSON은 UTF-8입니다. QR 응답은 PNG입니다.

| 메서드 | 경로 | 기능 |
|---|---|---|
| POST | `/api/teacher/evaluations` | 평가 및 PIN 생성 |
| GET | `/api/teacher/evaluations/{id}` | 평가 정보 및 PIN 조회 |
| GET | `/api/teacher/evaluations/{id}/qr` | 참여 URL이 담긴 300×300 QR PNG |
| GET | `/api/teacher/evaluations/{id}/participants` | 참가자 목록, 연결 상태, 평가 상태 |
| POST | `/api/teacher/evaluations/{id}/start` | 평가 시작 |
| POST | `/api/teacher/evaluations/{id}/end` | 평가 종료 |
| GET | `/api/participation/verify?pin=000001` | PIN 확인 |
| POST | `/api/participation/join` | 닉네임 등록 또는 토큰으로 재접속 |
| GET | `/api/evaluations/{id}/state` | 학생 화면 상태 조회 및 연결 갱신 |
| POST | `/api/evaluations/{id}/heartbeat` | 학생 연결 갱신 및 상태 조회 |
| POST | `/api/evaluations/{id}/leave` | 대기실/평가 이탈 |

### 평가 생성

```sh
curl -X POST http://localhost:8080/api/teacher/evaluations \
  -H "Authorization: Bearer $LIT_TEACHER_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"title":"수학 1차 평가","expiresInMinutes":60}'
```

`id`, `title`, `pin`, `state`, `expiresAt`, `joinUrl`, `qrUrl`을 반환합니다.
`expiresInMinutes`는 필수이며 1~1440분입니다. 제목은 공백 제외 1~100자입니다.
QR의 URL과 PIN은 같은 평가를 가리킵니다. QR URL은 교사 인증을 요구하므로 프런트에서는
인증 헤더로 fetch한 후 Blob URL을 만들어 표시하세요. 생성 실패 시 동일 요청을 다시 할 수 있습니다.
`joinUrl`의 웹 페이지는 별도 프런트엔드가 구현해야 합니다.

### 참여 확인 → 닉네임 등록

```sh
curl 'http://localhost:8080/api/participation/verify?pin=000001'
curl -X POST http://localhost:8080/api/participation/join \
  -H 'Content-Type: application/json' \
  -d '{"pin":"000001","nickname":"학생1"}'
```

위 PIN은 형식 예시입니다. 생성 응답의 실제 PIN을 사용하세요.
참여 응답은 `participantId`, `participantToken`, `lobby`를 반환합니다.
토큰은 클라이언트에 보관하고 재접속/상태 조회 시 사용하세요. 닉네임으로 참가자를 인증하지 않습니다.
PIN 앞뒤 공백은 제거하며 숫자형 JSON 대신 문자열로 전달해 앞자리 0을 보존하세요.

### 화면 동기화

교사는 참가자 목록을, 학생은 상태 API를 3~5초 간격으로 조회하세요.
학생 상태 조회 자체가 하트비트 역할을 합니다. 별도 heartbeat를 사용한다면 10초 간격을 권장합니다.

```sh
curl "http://localhost:8080/api/evaluations/EVALUATION_ID/state" \
  -H 'X-Participant-Token: PARTICIPANT_TOKEN'
curl -X POST "http://localhost:8080/api/teacher/evaluations/EVALUATION_ID/start" \
  -H "Authorization: Bearer $LIT_TEACHER_KEY"
```

`lobby`/상태 응답 예시:

```json
{
  "evaluationId":"uuid",
  "title":"수학 1차 평가",
  "state":"WAITING",
  "screen":"WAITING",
  "participantCount":1,
  "participants":[{"id":"uuid","nickname":"학생1","presence":"CONNECTED"}]
}
```

`state`: `WAITING → STARTED → ENDED`.
`screen`: `WAITING`, `SOLVING`, `SUBMITTED`, `ENDED`, `EXPIRED` 중 하나입니다.
제출한 학생의 상태 조회와 재접속 응답은 `SUBMITTED` 화면을 반환합니다.
학생 화면은 `screen`에 맞춰 이동하세요. 만료 상태는 종료 상태보다 우선하지 않습니다.
0명일 때 `participants: []` 및 `participantCount: 0`을 반환합니다.
`participantCount`는 이탈하지 않은 참가자 수이며, 연결 끊긴 참가자도 포함합니다.
연결된 참가자가 0명이면 시작할 수 없습니다.

### 이탈과 재접속

```sh
curl -X POST http://localhost:8080/api/evaluations/EVALUATION_ID/leave \
  -H 'X-Participant-Token: PARTICIPANT_TOKEN'
curl -X POST http://localhost:8080/api/participation/join \
  -H 'Content-Type: application/json' \
  -d '{"pin":"000001","participantToken":"PARTICIPANT_TOKEN"}'
```

재접속은 기존 참가자 ID와 닉네임을 유지합니다. 새 행을 만들지 않습니다.
재접속 시 전달한 nickname은 무시합니다. 이탈 후에는 재접속할 때까지 상태 조회를 허용하지 않습니다.

## 이미지에서 확정되지 않은 정책에 적용한 기본값

- PIN: 6자리 숫자 문자열, DB 전체 고유, 만료/종료 PIN 재사용 및 재발급 없음.
- 만료: 생성 시 지정한 시각 이후에는 신규 참여, 재접속, 시작 불가. 이미 참여한 학생의 화면은 `EXPIRED`.
- 종료: PIN 확인/신규 참여/재접속 불가. 기존 학생은 `ENDED` 화면 조회 가능.
- 시작: 연결된 참가자가 1명 이상일 때만 가능. 중복 시작은 409. 평가 행 잠금으로 동시 시작/참여를 직렬화.
- 지연 참여: 시작 후 새 참가자는 참여 불가. 기존 토큰 소유자는 재접속 가능.
- 닉네임: 앞뒤 공백 제거, 공백만 입력 불가, Unicode 코드포인트 기준 1~20자, 같은 평가에서 대소문자를 구분하여 중복 금지.
- 이탈 후 닉네임은 기존 참가자에게 예약. 닉네임 변경 API 없음.
- 연결 끊김: 30초 동안 상태 조회/하트비트가 없으면 `DISCONNECTED`, 재접속 시 `CONNECTED`.
- PIN 확인과 참여 요청은 IP당 합산 분당 30회 제한. 단일 서버 메모리 기반이며 재시작 시 초기화.
  프록시 뒤에서는 remoteAddr가 프록시 IP일 수 있으므로 운영 시 게이트웨이 제한 정책으로 대체하세요.
- 서버 push 대신 폴링 사용. 아래 추가 기능에서 문항 등록·답안 제출을 지원합니다. 화면 UI와 채점은 별도 구현 범위입니다.

## 오류 형식

```json
{"code":"PIN_EXPIRED","message":"만료된 평가입니다."}
```

| 상태 | 대표 code |
|---|---|
| 400 | INVALID_REQUEST, INVALID_PIN, INVALID_NICKNAME |
| 401 | TEACHER_UNAUTHORIZED, PARTICIPANT_TOKEN_REQUIRED, INVALID_PARTICIPANT_TOKEN |
| 404 | PIN_NOT_FOUND, EVALUATION_NOT_FOUND |
| 409 | EVALUATION_ENDED, ALREADY_STARTED, NO_PARTICIPANTS, NICKNAME_TAKEN, PIN_COLLISION |
| 410 | PIN_EXPIRED |
| 429 | RATE_LIMITED |
| 503 | TEACHER_KEY_NOT_CONFIGURED, PIN_UNAVAILABLE |

테스트는 HTTP 전체 참여 흐름, QR 생성, 재접속 중복 방지, 연결 상태, 이탈, 종료,
입력 검증, 인증, 만료, 앞자리 0 보존, 동시 시작을 검증합니다.

## 추가 기능: Postman으로 전체 흐름 실행

기존 서버가 켜져 있으면 종료한 후, 변경된 코드로 다시 실행하세요.

```sh
cd /Users/dgsw10/Desktop/LIT
export LIT_TEACHER_KEY='test'
export LIT_AI_PROVIDER='stub'
./mvnw spring-boot:run
```

Postman → Import → `postman/LIT.postman_collection.json`을 선택하고 **01번부터 순서대로** 실행하세요.
컬렉션의 `baseUrl`은 `http://localhost:8080`, `teacherKey`는 위의 `test`로 지정되어 있습니다.
평가 ID, PIN, 참가자 토큰, 문항 ID, AI 요청 ID와 제출 확인 토큰을 응답에서 자동 저장합니다.
컬렉션 전체를 다시 실행할 때도 01번부터 실행해 새 평가를 생성하세요.
개별 저장 요청만 재실행한다면 최신 답안의 `version`을 먼저 조회해 `expectedVersion`을 수정해야 합니다.

`stub`은 **실제 AI를 호출하지 않는 테스트 응답**입니다. 응답에 `[테스트 응답]`을 표시하고
기록에도 `provider: stub`, `model: local-stub`을 남깁니다.

### 실제 AI 연결

```sh
export LIT_AI_PROVIDER='openai'
export OPENAI_API_KEY='본인의-OpenAI-API-키'
export OPENAI_MODEL='본인 계정에서 사용할 모델 ID'
./mvnw spring-boot:run
```

별도 모델 ID를 고정하지 않았습니다. 키와 모델은 서버 환경변수에만 설정하며 학생에게 전달하지 않습니다.
키/모델 미설정 시 AI 요청만 503 `AI_NOT_CONFIGURED`로 실패하고 나머지 기능은 사용할 수 있습니다.
서버에서 [OpenAI 공식 Responses API](https://developers.openai.com/api/docs/guides/text)를 호출합니다.
서버에 현재 문항, 학생의 저장된 초안, 해당 문항의 최근 완료 대화 최대 20회를 전달하고 `store: false`로 요청합니다.
기본 URL은 `https://api.openai.com/v1/responses`, 연결 제한은 5초, 요청 제한은 45초입니다.
`OPENAI_RESPONSES_URL`, `LIT_AI_TIMEOUT_SECONDS`로 서버 설정을 변경할 수 있습니다.
타임아웃은 1~60초만 허용합니다. 테스트는 로컬 HTTP 서버로 요청/응답 형식, 오류, 타임아웃을 검증하며 실제 유료 API를 호출하지 않습니다.

## 문항 및 AI 설정 API

교사 인증은 기존과 같습니다. 평가 시작 **이전**에만 문항·AI 정책을 설정할 수 있습니다.
문항은 1~100개, 순서 `position`은 양수이며 평가 내에서 유일해야 합니다.
제목은 1~200자, 본문은 1~10,000자입니다. 제목 중복은 허용하며 문항은 UUID로 구분합니다.
설정 PUT은 문항 목록을 전체 교체하므로 문항 ID가 다시 발급됩니다.

```http
PUT /api/teacher/evaluations/{id}/questions
Authorization: Bearer test
Content-Type: application/json
```

```json
{
  "aiPolicy": "HINT_ONLY",
  "questions": [
    {"position": 1, "title": "문항 1", "content": "2+2의 값과 이유를 설명하세요."},
    {"position": 2, "title": "문항 2", "content": "풀이 과정을 설명하세요."}
  ]
}
```

`GET /api/teacher/evaluations/{id}/questions`로 문항 ID와 설정을 조회할 수 있습니다.

| aiPolicy | 허용 범위 |
|---|---|
| DISABLED | AI 질문 불가. 미설정 평가의 기본값 |
| HINT_ONLY | 개념 설명, 유도 질문, 힌트만 허용하도록 모델에 지시 |
| FULL | 풀이 설명과 답안 예시 허용 |

정책은 서버가 적용하며 학생 요청에서 변경할 수 없습니다.
`HINT_ONLY`는 모델 지시로 적용하는 정책으로, 출력에 정답이 절대 포함되지 않는다는 보장은 아닙니다.
이번 구현의 ‘단계’는 평가 전체 AI 허용 수준입니다. 시간별/문항별 단계 전환은 추가 정책이 필요합니다.

## 학생 풀이 API

이하 모든 요청은 `X-Participant-Token` 헤더를 사용합니다.
평가 시작 전에는 문항/대화를 열람할 수 없습니다. 종료·만료 후에는 본인 기록 조회만 가능합니다.

| 메서드 | 경로 | 기능 |
|---|---|---|
| GET | `/api/evaluations/{id}/questions` | 문항·저장 답안·AI 정책·읽기 전용 상태 |
| GET | `/api/evaluations/{id}/questions/{questionId}` | 문항 전환 시 해당 문제와 본인 저장 답안 |
| PUT | `/api/evaluations/{id}/questions/{questionId}/answer` | 문항별 답안 저장 및 수정 |
| POST | `/api/evaluations/{id}/questions/{questionId}/ai/questions` | 문항별 AI 질문 |
| GET | `/api/evaluations/{id}/conversations` | 본인의 AI 질문·응답 기록 |
| GET | `/api/evaluations/{id}/learning-events` | 본인의 학습 과정 기록 |
| GET | `/api/evaluations/{id}/submission-preview` | 제출 확인용 내용·미작성 문항·확인 토큰 |
| POST | `/api/evaluations/{id}/submission` | 최종 제출 확정 |
| GET | `/api/evaluations/{id}/submission` | 제출 확정 시점의 고정된 기록 |

문항 목록은 순서대로 반환됩니다. 각 문항은 `question`과 `answer`로 구성됩니다.
저장 전 답안은 `content: ""`, `version: 0`, `updatedAt: null`입니다.
`status`는 `IN_PROGRESS`, `SUBMITTED`, `ENDED`, `EXPIRED`이며 `readOnly`를 함께 반환합니다.

### 답안 저장과 문항 전환

```json
{
  "content": "내 답안",
  "expectedVersion": 0,
  "source": "DIRECT"
}
```

본문은 일반 텍스트이며 최대 10,000자입니다. 빈 답안 저장도 허용합니다.
성공 시 `questionId`, `content`, 증가한 `version`, `updatedAt`을 반환합니다.
`expectedVersion`은 마지막 조회/저장 응답의 버전을 전달하세요.
버전이 다르면 409 `ANSWER_VERSION_CONFLICT`이며 최신 답안을 다시 조회해야 합니다.
같은 저장 요청의 재전송은 버전 충돌로 차단되어 이벤트가 중복 저장되거나 최신 답안이 역전되지 않습니다.

문항 전환은 별도 서버 선택 상태를 만들지 않고 문항 ID로 조회합니다.
프런트에서는 전환 **전에 저장 성공을 확인**하고, 저장 실패 시 현재 입력을 유지한 채 재시도해야 합니다.
서버가 받지 못한 클라이언트 입력은 보존할 수 없습니다. 웹 화면의 저장 중/실패 표시와 브라우저 초안 보관은 프런트 구현 항목입니다.

`source` 값은 `DIRECT`, `AI_REFERENCED`, `AI_USED`입니다.
AI 사용/참고인 경우 같은 학생·같은 문항의 완료된 AI 대화 `aiRequestId`를 함께 전달해야 합니다.
예: `{"content":"수정 답안","expectedVersion":1,"source":"AI_REFERENCED","aiRequestId":"ask-001"}`.
이 분류는 학생의 명시적 신고이며 서버가 외부 AI 사용을 자동으로 감지하지 않습니다.

### AI 질문과 대화 이력

```json
{"requestId":"ask-001","text":"이 문제의 접근 방법을 알려주세요."}
```

`requestId`는 클라이언트에서 새 질문마다 생성하는 영문·숫자·`_`·`-`의 1~64자 식별자입니다.
질문은 공백 제외 1~4,000자입니다.
같은 질문을 네트워크 재시도할 때는 **같은 requestId와 본문**을 사용하세요.
완료된 요청은 기존 응답을 돌려주며 진행 중인 중복 요청은 202와 `PENDING`을 반환합니다.
같은 ID에 다른 문항/본문을 보내면 409 `IDEMPOTENCY_KEY_REUSED`입니다.

한 참가자당 동시에 1개, 분당 최대 10개, 평가당 최대 100개의 새 AI 요청을 허용합니다.
다른 질문을 중복 전송하면 409 `AI_IN_PROGRESS`입니다.
상태는 `PENDING`, `COMPLETED`, `FAILED`이며 실패 시 `errorCode`를 반환합니다.
외부 응답이 실패해도 질문과 실패 이벤트를 DB에 남깁니다.
실패한 요청의 동일 ID 재시도는 실패 기록을 그대로 반환합니다. 사용자가 명시적으로 다시 질문할 경우 새 requestId를 사용하세요.
타임아웃/서버 중단 시 외부 제공자가 요청을 이미 처리했을 수 있어 자동 재호출하지 않습니다.
서버 중단으로 120초 이상 남은 PENDING은 30초 주기 작업에서 FAILED로 복구합니다.

- 완료: HTTP 200
- 진행 중인 동일 요청: HTTP 202
- 키/모델 미설정: HTTP 503, `AI_NOT_CONFIGURED`
- 응답 시간 초과: HTTP 504, `AI_TIMEOUT`
- 제공자 오류/제한/불완전 응답: HTTP 502, 각각의 `errorCode`

이 AI 처리 결과 응답은 일반 오류 envelope 대신 `Turn` 객체에 `state`, `errorCode`, `requestId`를 포함합니다.
각 Turn은 질문/응답 원문, 문항 ID, 모델/도구 정보, 허용 정책, 질문 시점 답안 버전, 생성/완료 시각을 포함합니다.
제공자가 지원하는 웹 검색 등 별도 도구를 켜지 않으며 provider는 호출한 AI 서비스 식별자입니다.

대화 조회는 `?questionId=...&afterId=0&limit=100`을 지원합니다. questionId는 선택입니다.
시간순 정렬은 서버 발급 증가 ID로 보장합니다. 다음 조회는 마지막 ID를 `afterId`로 전달하세요.
`limit`은 1~200입니다. 새 메시지 스크롤 처리는 프런트에서 담당합니다.

### 학습 과정 자동 기록

답안 변경과 이벤트 기록을 하나의 DB 트랜잭션으로 처리합니다.
답안의 변경 전/후 원문·버전, 직접 작성/AI 참고·사용 분류, 참조한 AI 요청 ID,
AI 질문/응답 원문·모델·실패 원인·발생 시각, 제출 확정을 기록합니다.
기록은 서버 발급 ID 순서로 조회하며 `afterId`, `limit`을 지원합니다.
교사는 다음 인증 경로로 평가에 속한 참가자 기록을 조회할 수 있습니다.

`GET /api/teacher/evaluations/{id}/participants/{participantId}/learning-events`

학생 요청에는 타인의 participantId를 지정하는 기능이 없으며 서버가 토큰에서 신원을 확인합니다.
외부 AI 사용이나 저장되지 않은 키 입력은 자동 기록 대상이 아닙니다.

## 제출 미리 확인 → 최종 확정

1. `GET /submission-preview`로 저장된 문항별 답안, 대화, 학습 과정, `blankQuestionIds`, `reviewToken`을 받습니다.
2. 프런트에서 내용과 미작성 안내를 보여주세요. 취소는 창만 닫으며 API 호출/서버 상태 변경이 없습니다.
3. 확정 시 아래 요청을 보냅니다.

```json
{"reviewToken":"미리보기에서 받은 64자리 확인 토큰"}
```

미리보기 후 답안이나 대화가 바뀌면 409 `REVIEW_CHANGED`입니다. 내용을 다시 확인하세요.
AI 처리가 진행 중이면 `canConfirm: false`이며 제출 확정은 409 `AI_IN_PROGRESS`로 거절합니다.
미작성 문항도 안내 후 제출할 수 있도록 했습니다. 문항이 0개인 평가는 제출할 수 없습니다.
최종 제출은 답안·대화·학습 과정의 JSON snapshot을 같은 트랜잭션에서 저장합니다.
응답은 `status: SUBMITTED`, `submittedAt`, `snapshot`입니다.
제출 후에는 답안 수정/새 AI 질문/재제출을 차단하고 기존 기록 조회만 허용합니다.
같은 reviewToken으로 재시도한 제출 확정은 저장된 제출 결과를 그대로 반환합니다.
제출 완료 화면은 기존 `/state`, `/heartbeat`, 재접속에서도 `SUBMITTED`로 안내합니다.

기존 데이터는 삭제하지 않고 `CREATE TABLE IF NOT EXISTS`로 추가 테이블을 생성합니다.
기존 평가에는 기본적으로 문항이 없고 AI가 비활성화됩니다. 새 풀이 흐름은 문항을 설정한 평가로 테스트하세요.
