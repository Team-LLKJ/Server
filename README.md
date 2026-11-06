# LIT Server

Spring Boot 3.5.16 / Java 17+ 기반 평가 참여·풀이·제출 및 교사 Auth·학생 비회원 참여 API입니다.
화면 UI와 채점 기능은 포함하지 않습니다.

## 로컬 실행

Java 17 이상이 필요하며 Maven Wrapper가 포함되어 있습니다.

```sh
cd /Users/dgsw10/Desktop/LIT
export LIT_AI_PROVIDER='stub'
./mvnw spring-boot:run
```

API 주소는 `http://localhost:5174`입니다. 신규 가입에는 이메일 인증이 필요합니다.
[이메일 인증 설정](docs/EMAIL_SIGNUP.md)에 따라 SMTP와 발신 주소를 설정하세요. 설정 전에도 기존 계정 로그인은 가능하지만 신규 인증메일 발송은 503으로 거절됩니다.
`LIT_AI_PROVIDER=stub`은 실제 AI를 호출하지 않고 명시적으로 `[테스트 응답]`을 반환합니다.

```sh
./mvnw test
./mvnw package
java -jar target/lit-api-0.0.1-SNAPSHOT.jar
```

## DB

기본값은 별도 DB 서버가 필요 없는 파일형 H2입니다.
프로젝트 디렉터리에서 실행하면 `data/lit.mv.db`에 저장되며 재시작 후 유지됩니다.
DB·빌드 결과·환경변수 파일은 Git에 포함하지 않습니다.
테스트는 별도의 메모리 DB를 사용하므로 로컬 파일 DB를 변경하지 않습니다.

| 환경변수 | 기본값 |
|---|---|
| DATABASE_URL | `jdbc:h2:file:./data/lit` |
| DATABASE_USER | `sa` |
| DATABASE_PASSWORD | 빈 값 |

현재 스키마의 `MERGE`, `DATEADD`, `IF NOT EXISTS` 등은 H2 기준입니다.
MySQL/PostgreSQL 이전에는 드라이버, DB별 SQL과 버전형 마이그레이션을 맞춰야 합니다.
기존 DB에는 테이블·nullable 소유자 컬럼을 추가하고 기존 기록을 삭제하지 않습니다.
기존 평가/참가 기록을 새 계정에 자동 귀속하지 않습니다.

## 인증

이메일 소유를 인증한 사용자가 가입합니다. 학교·초대 코드·교사 신분 확인·관리자 승인은 요구하지 않습니다. `TEACHER`는 교사 신분 보증이 아니라 평가 생성·운영용 서비스 역할입니다.
교사는 `/api/auth/login`으로 로그인하고 교사용 API에 다음 헤더를 사용합니다.

```http
Authorization: Bearer {accessToken}
```

학생은 가입·로그인 없이 PIN과 닉네임으로 참여합니다. 참여 응답의 participantToken을 학생 API의 `X-Participant-Token` 헤더에 사용하세요.
교사 공통 키는 사용하지 않습니다.
Access Token은 15분, Refresh Token은 최초 로그인 기준 최대 14일이며 회전·재사용 감지·로그아웃을 지원합니다.
토큰은 opaque 난수 방식이며 DB에는 해시만 저장합니다. 역할은 DB에서 확인합니다.

[Auth 상세 명세](docs/AUTH.md)에 가입 Body, 토큰 응답, 이메일 인증·오류 정책을 정리했습니다.

## API 목록

### Auth

| 메서드 | 경로 | 기능 |
|---|---|---|
| POST | `/api/auth/signup/teacher` | 이메일 인증 완료 후 가입 |
| POST | `/api/auth/login` | 공통 로그인 |
| POST | `/api/auth/refresh` | 토큰 교체 |
| GET | `/api/auth/me` | 본인 계정 |
| POST | `/api/auth/logout` | 현재 로그인 세션 폐기 |
| GET | `/api/auth/username-availability?username=...` | 아이디 중복 확인 |
| POST | `/api/auth/email-verifications` | 가입 인증메일 발송 |
| POST | `/api/auth/email-verifications/confirm` | 인증번호 확인·가입용 토큰 발급 |
| POST | `/api/auth/teacher-invitations` | 종료된 기능(410) |

### 평가·참여

교사용 `/api/teacher/**`는 교사 로그인 및 평가 소유권을 검사합니다.
PIN 확인·참여는 공개입니다. 학생 풀이 API는 로그인 대신 평가별 참가자 토큰이 필요합니다.

| 메서드 | 경로 | 기능 |
|---|---|---|
| POST | `/api/teacher/evaluations` | 평가 생성 |
| GET | `/api/teacher/evaluations?page=0&size=20` | 내가 만든 평가 목록 |
| GET | `/api/teacher/evaluations/{id}` | 평가 정보·PIN |
| PATCH | `/api/teacher/evaluations/{id}` | 제목·풀이 시간·PIN 유효 시간 수정 (WAITING만) |
| DELETE | `/api/teacher/evaluations/{id}` | 평가와 관련 기록 삭제 (진행 중 불가) |
| PUT | `/api/teacher/evaluations/{id}/content` | 평가 기준·규칙 저장 (대기 중 소유 교사) |
| GET | `/api/teacher/evaluations/{id}/content` | 저장한 평가 내용 조회 (소유 교사) |
| GET | `/api/evaluations/{id}/content` | 평가 내용 조회 (해당 평가 참가자) |
| GET | `/api/teacher/evaluations/{id}/qr` | 참여 URL QR PNG |
| GET | `/api/teacher/evaluations/{id}/participants` | 참가자 목록 |
| POST | `/api/teacher/evaluations/{id}/start` | 평가 시작 |
| POST | `/api/teacher/evaluations/{id}/end` | 평가 종료 |
| PUT | `/api/teacher/evaluations/{id}/questions` | 시작 전 문항·AI 정책 전체 설정 |
| GET | `/api/teacher/evaluations/{id}/questions` | 문항·AI 정책 조회 |
| GET | `/api/teacher/evaluations/{id}/participants/{participantId}/learning-events` | 학생 학습 기록 |
| GET | `/api/participation/verify?pin=000001` | PIN 확인 |
| POST | `/api/participation/join` | 비회원 학생의 참여·재접속 |
| GET | `/api/evaluations/{id}/state` | 학생 상태 및 연결 갱신 |
| POST | `/api/evaluations/{id}/heartbeat` | 연결 갱신 |
| POST | `/api/evaluations/{id}/leave` | 이탈 |
| GET | `/api/evaluations/{id}/questions` | 문항·본인 답안 목록 |
| GET | `/api/evaluations/{id}/questions/{questionId}` | 문항·본인 답안 조회 |
| PUT | `/api/evaluations/{id}/questions/{questionId}/answer` | 문항별 답안 임시저장·수정 |
| GET | `/api/evaluations/{id}/questions/{questionId}/answer` | 해당 문항의 저장 답안·버전·저장 시각 조회 |
| POST | `/api/evaluations/{id}/questions/{questionId}/ai/questions` | AI 질문 |
| GET | `/api/evaluations/{id}/conversations` | 본인 대화 |
| GET | `/api/evaluations/{id}/learning-events` | 본인 학습 기록 |
| POST | `/api/evaluations/{id}/learning-events` | 창 이탈·캡처·복사 시도·붙여넣기·AI 답변 복사 기록 |
| GET·PUT | `/api/evaluations/{id}/ai-disclosure` | 사용한 AI 도구·활용 부분·선택 이유 |
| GET | `/api/evaluations/{id}/submission-preview` | 제출 내용 확인 |
| POST | `/api/evaluations/{id}/submission` | 최종 제출 확정 |
| GET | `/api/evaluations/{id}/submission` | 확정된 기록 조회 |

평가 생성: `{"title":"수학 평가","expiresInMinutes":60}`. 제목 1~100자, PIN 유효 시간 1~1440분. `durationMinutes`(1~1440, 생략 시 expiresInMinutes)는 시작 시점부터 적용되는 제한 시간입니다.
문항 설정: `{"aiPolicy":"HINT_ONLY","questions":[{"position":1,"title":"문항1","content":"2+2는?"}]}`.
문항 1~100개, 양수 순서 중복 불가. 제목 최대 200자, 본문 최대 10,000자.
참여: `{"pin":"012345","nickname":"학생1"}`. PIN은 6자리 문자열, 닉네임은 1~20자.
참여 응답은 participantId·participantToken·lobby를 반환합니다.
재접속은 `{"pin":"012345","participantToken":"기존 토큰"}`으로 호출하며 참가자 ID·답안·닉네임을 유지합니다.

### 교사 채점 화면 (AI 활용 평가 기준, 100점)

상세: [docs/AI_GRADING.md](docs/AI_GRADING.md), 프런트 요청 답변: [docs/FRONTEND_REQUESTS_2026-10-31.md](docs/FRONTEND_REQUESTS_2026-10-31.md)

| 메서드 | 경로 (`/api/teacher/evaluations/{id}` 기준) | 기능 |
|---|---|---|
| GET | `/submissions` | 학생별 제출·경고·채점 상태·총점 |
| GET | `/participants/{pid}/answers` | 답안과 문단별 출처(추정) |
| GET | `/participants/{pid}/conversations` | AI 대화와 질문 유형 |
| GET | `/participants/{pid}/analysis` | 규칙 기반 활용 분석(정책 점검·유사도 참고·질문 유형) |
| POST | `/analysis/run` | 제출 학생 전체 재분석 |
| GET·PUT·DELETE | `/rubric` | 평가 기준·배점 (채점 시작 전 변경) |
| GET | `/participants/{pid}/rubric-scores` | AI 추천·교사 점수 |
| PUT | `/participants/{pid}/rubric-scores/{criterionId}` | 기준 하나 저장 `{level,feedback}` |
| GET | `/participants/{pid}/rubric-scores/history` | 점수 변경 이력 |
| POST | `/participants/{pid}/grade/confirm` | 채점 확정 |
| PUT | `/participants/{pid}/compliance-review` | 허용 범위 교사 판단(판단 보류 등) |

### 알림·내 정보

| 메서드 | 경로 | 기능 |
|---|---|---|
| GET | `/api/notifications?page=0&size=20&unreadOnly=false` | 알림 목록 |
| GET | `/api/notifications/unread-count` | 읽지 않은 개수 |
| PATCH | `/api/notifications/{id}/read` | 하나 읽음 |
| POST | `/api/notifications/read-all` | 모두 읽음 |
| GET·PUT | `/api/users/me/settings` | 알림 받기·소리 설정 |
| GET·PATCH | `/api/users/me` | 내 정보·이름 변경 |
| POST | `/api/users/me/password` | 비밀번호 변경 (다른 기기 로그인 해제) |

## 답안·AI·제출 정책

- 답안 저장: `{"content":"답안","expectedVersion":0,"source":"DIRECT"}`.
- content 입력 최대 UTF-8 1MiB, 표 HTML·빈 답안 허용. 현재 버전이 일치해야 저장되며 성공 시 version +1.
- source는 DIRECT / AI_REFERENCED / AI_USED. AI 참고·사용은 같은 참가자·문항의 완료된 aiRequestId 필요.
- 문항 전환 전 저장 성공을 확인하세요. 프런트의 미저장 입력 보존·실패 표시는 별도 구현 항목입니다.
- AI 정책은 AIAS 5단계: DISABLED(1) / HINT_ONLY(2) / EDIT_ASSIST(3) / TASK_ASSIST(4) / FULL(5). 설정 시 aiPolicy 또는 aiLevel(1~5)을 보냅니다. HINT_ONLY·EDIT_ASSIST·TASK_ASSIST는 모델에 지시하는 제한으로 정답 미노출을 보장하지는 않습니다.
- AI 질문: `{"requestId":"ask-001","text":"힌트를 주세요"}`. text 최대 4,000자.
- requestId는 `[A-Za-z0-9_-]{1,64}`. 같은 요청 재시도는 같은 ID·본문, 새 질문은 새 ID.
- 참가자당 동시 1건·분당 10건·평가당 100건. 실패도 기록되며 자동 재호출하지 않습니다.
- AI 상태 PENDING / COMPLETED / FAILED. 진행 중 중복 요청은 202, 완료는 200, 제공자 실패는 502/503/504 및 Turn.errorCode.
- 대화·학습 기록은 `afterId=0&limit=100` 커서 조회. limit은 1~200. 대화에는 선택적 questionId 필터가 있습니다.
- 제출은 미리 확인 응답의 reviewToken을 `{"reviewToken":"..."}`으로 전달합니다. 내용 변경 시 409 REVIEW_CHANGED.
- AI 진행 중에는 제출 불가. 빈 답안은 확인 후 제출 가능. 제출 후 답안·대화는 읽기 전용입니다.
- 교사 종료·시간 만료 시 미제출자는 자동 제출되며, 종료 후 저장은 기본적으로 거절합니다. 선택 설정 `LIT_EVALUATION_END_GRACE_SECONDS`의 기본값은0이며, 별도 합의로 켜더라도 채점·리포트가 있는 답안은 변경하지 못합니다.
- 평가 state는 WAITING / STARTED / ENDED. 학생 screen은 WAITING / SOLVING / SUBMITTED / ENDED / EXPIRED.
- 연결 갱신이 30초 없으면 DISCONNECTED. 상태 조회를 3~5초 간격, 별도 heartbeat는 10초 간격으로 호출하세요.

## Postman

`postman/LIT.postman_collection.json`을 Import하고 먼저 **이메일 인증 회원가입** 폴더에서 실수신 가능한 이메일로 인증을 완료하세요.
**전체 흐름 (테스트)** 폴더를 순서대로 실행하면 교사 가입·로그인·학생 비회원 참여·평가·답안·AI·제출·교사 토큰 갱신·로그아웃까지 진행합니다.
계정 이름·비밀번호는 첫 요청에서 테스트용으로 생성하고 토큰·ID는 응답에서 자동 저장합니다.
가입 API는 일회용 이메일 인증 토큰을 소비합니다. 인증번호는 메일에서 직접 입력하며 API 응답에는 포함되지 않습니다.
가입 이후의 개별 요청은 저장된 변수로 재실행할 수 있으나 답안 expectedVersion과 제출 상태를 확인하세요.

## 외부 서비스 설정

실제 AI 기본 제공자는 Gemini입니다. 백엔드를 실행하는 터미널에 `LIT_AI_PROVIDER=gemini`, `GEMINI_API_KEY`, `GEMINI_MODEL`을 설정합니다. 모델 이름은 Google AI Studio에서 프로젝트에 사용 가능한 모델로 지정하세요. 키는 프런트엔드의 `VITE_` 변수나 Git에 넣지 않습니다.

```sh
export LIT_AI_PROVIDER=gemini
read -s 'GEMINI_API_KEY?Gemini API key: '
export GEMINI_API_KEY
export GEMINI_MODEL='<AI Studio에서 선택한 모델 ID>'
./mvnw spring-boot:run
```

위 키 입력 명령은 macOS zsh 기준이며 입력값을 화면에 표시하지 않습니다. 기존 서버는 해당 터미널에서 Ctrl+C로 종료한 후 한 번만 실행합니다. 이미 실행 중인 서버에는 새 환경변수가 적용되지 않습니다.

학생의 `POST /api/evaluations/{id}/questions/{questionId}/ai/questions` 요청은 `X-Participant-Token` 헤더와 `{ "requestId": "고유한 요청 ID", "text": "질문 내용" }`를 사용합니다. 같은 요청 재시도에는 같은 requestId를 사용합니다. 서버가 문항, 현재 답안과 최근 대화를 Gemini에 전달하며 질문·응답·제공자·모델·시각·실패 상태를 DB에 남깁니다. `GET /api/evaluations/{id}/conversations`로 본인 대화를 다시 조회합니다. 교사는 기존 학습 이벤트 조회 API로 기록을 확인합니다. 제출 후 신규 AI 질문은 차단합니다.

키나 모델이 없으면 실제 호출 없이 `AI_NOT_CONFIGURED`, 무료 할당량 초과 시 `AI_PROVIDER_RATE_LIMITED`를 기록합니다. 무료 등급에서 한도에 도달해도 자동으로 유료 모델로 전환하지 않습니다.

기존 OpenAI 제공자도 지원합니다. `LIT_AI_PROVIDER=openai`, `GEMINI_API_KEY` 대신 `OPENAI_API_KEY`, `GEMINI_MODEL` 대신 `OPENAI_MODEL`을 사용하며, 커스텀 URL은 `LIT_AI_ENDPOINT`로 지정합니다.
Auth 개발·테스트는 외부 AI 호출 없이 stub을 사용합니다.
`LIT_JOIN_BASE_URL` 기본값은 `http://localhost:3000/join`이며 참여 웹 페이지는 별도로 필요합니다.


## 검증

인증·역할·소유권·이메일 인증·갱신 재사용·로그아웃·계정 제한,
전체 평가 흐름·답안 충돌·AI 대화 격리·제출 고정·이전 스키마 보존을 자동 테스트합니다.

기술 참고: [Spring Security 구조](https://docs.spring.io/spring-security/reference/servlet/architecture.html),
[비밀번호 저장](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).

학생 회원가입은 비활성화되어 `/api/auth/signup/student` 호출 시 410 STUDENT_ACCOUNTS_DISABLED를 반환합니다. 기존 STUDENT 계정도 로그인·갱신을 허용하지 않습니다.

비밀번호 찾기·재설정 기능은 제외했습니다. 회원가입 인증메일은 SMTP 설정이 필요하며, 대표 색상 `#007BFF`의 HTML 본문과 일반 텍스트 대체 본문으로 발송합니다.


### 내가 만든 수행평가 조회

`GET /api/teacher/evaluations?page=0&size=20`에 교사 로그인 응답의 `Authorization: Bearer {accessToken}`을 전달합니다. 학생 참가자 토큰으로는 조회할 수 없습니다. 본인이 만든 평가만 포함하며 종료·만료된 평가도 반환합니다. 페이지는 0부터, size는 1~100(기본20)입니다. 만료 시각 내림차순, 같은 만료 시각은 id 내림차순입니다. 현재 스키마에 생성 시각이 없어 생성순을 의미하지 않습니다.

```json
{
  "items": [
    {
      "id": "평가 UUID",
      "title": "수학 수행평가",
      "pin": "012345",
      "state": "WAITING",
      "expiresAt": "2026-10-28T03:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

평가가 없거나 페이지 범위를 넘으면 items는 빈 배열입니다. 잘못된 page/size는400, 인증 누락·만료는401입니다. 프런트는 선택한 items[].id로 기존 평가 상세·문항·참가자 조회 API를 호출합니다.

### 프런트에서 Gemini 대화 연결

학생은 가입 없이 PIN 참여 후 받은 participantToken을 사용합니다. Gemini 키는 서버에만 설정하며 아래 요청에는 넣지 않습니다. 프런트·백엔드가 다른 컴퓨터라면 프런트의 Vite `/api` 프록시 대상을 백엔드 IP:5174로 설정해야 합니다.

```js
const response = await fetch(`/api/evaluations/${evaluationId}/questions/${questionId}/ai/questions`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Participant-Token': participantToken,
  },
  body: JSON.stringify({ requestId: crypto.randomUUID(), text: questionText }),
});
const result = await response.json();
if (!response.ok || result.state === 'FAILED') {
  throw new Error(result.errorCode || result.code || 'AI_REQUEST_FAILED');
}
// COMPLETED이면 result.answerText 표시. PENDING이면 대화 조회로 상태 확인.
```

질문 버튼 처리의 finally에서 로딩을 해제하고 실패 메시지를 표시하세요. 새 질문마다 새 requestId, 네트워크 재시도는 원래 requestId·본문을 그대로 사용합니다. 대화는 같은 참가자 헤더로 `GET /api/evaluations/{id}/conversations?questionId={questionId}`에서 조회합니다. 평가가 STARTED이고 aiPolicy가 HINT_ONLY 또는 FULL이어야 질문할 수 있습니다.


## 교사 검토·리포트

학생 목록·상세, 기록 근거 기반 활용 분류, 교사 5개 영역 검토, 제출 리포트 버전 생성·조회, PDF 다운로드를 지원합니다. 자세한 요청·응답과 임시 평가 기준은 [docs/REPORTS.md](docs/REPORTS.md)를 참고하세요. 기존 리뷰 점수는 교사가 입력합니다. 새 Assessment API는 기록이 있는 항목의 규칙 기반 추천을 별도로 제공하며 최종 성적은 교사가 확정합니다. 기여도는 자동 추천하지 않습니다. POST reports는 제출 확정 이후에만 가능하며 백그라운드 생성 주기는30초입니다.

### Gemini 오류 진단

2026-11-01 운영 확인에서는 `gemini-3.1-flash-lite`가 반복503을 반환했고, 같은 키로 `gemini-3.5-flash-lite`는200과 정상 텍스트를 반환했습니다. `GEMINI_MODEL=gemini-3.5-flash-lite`를 현재 운영 설정 예시로 사용합니다. 모델의 가용성·요금·프로젝트 한도는 변할 수 있으므로 실제 사용할 키로 호출해 검증하세요. 모델 목록 조회만으로 생성 호출 권한을 보장하지 않습니다. 모델을 자동 교체하거나 유료 결제를 활성화하지 않습니다.

오류는 Turn(state=FAILED,errorCode=...)로 반환합니다. AI_MODEL_UNAVAILABLE(HTTP502): 모델404; AI_AUTHENTICATION_FAILED(502): 키·권한 오류; AI_INVALID_PROVIDER_REQUEST(502): 제공자 요청400; AI_PROVIDER_UNAVAILABLE(502): 제공자5xx; AI_PROVIDER_RATE_LIMITED(502): 제공자429; AI_NOT_CONFIGURED(503): 서버 키·모델 미설정; AI_TIMEOUT(504): 응답 제한 초과. 키 원문·제공자 원문 오류는 반환하거나 로그에 기록하지 않습니다.

Gemini 일시적 HTTP500/502/503/504는 같은 질문 처리 안에서 최대3회(최초1회+재시도2회), 기본1초·2초 간격으로 재시도합니다. 숫자 Retry-After가 더 길면 해당 간격을 따르되 전체 LIT_AI_TIMEOUT_SECONDS(기본45초)를 넘기지 않습니다. 키 오류·모델 오류·429·네트워크 시간 초과는 자동 재시도하지 않습니다. 모델 자동 전환도 하지 않습니다. 재시도는 새 대화 기록을 만들지 않으며 최종 결과만 기존 Turn에 반영합니다.

## CI/CD 배포

GitHub Actions 테스트·Docker 실행 점검·GHCR 이미지 게시·EC2 배포 설정은 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)를 참고하세요. 서버와 production 환경 Secrets를 설정한 뒤 develop 변경으로 배포합니다. 실제 EC2 연결은 별도 설정이 필요합니다.

종료 시각·서버 시계·자동 제출은 [제한 시간 API](docs/TIME_LIMITS.md)를 참고하세요.

프런트 guidelines·창 이탈 이벤트·1MiB HTML 자동 저장 계약은 [프런트 연동 API](docs/FRONTEND_CONTRACT.md)를 참고하세요.

평가 내용 저장·학생 공유 요청 예시는 [평가 내용 API](docs/EVALUATION_CONTENT.md)를 참고하세요.

교사 채점 화면의 검색·답안·대화·활동·20점 개별 리뷰·이력 연동은 [채점 화면 API](docs/TEACHER_GRADING.md)를 참고하세요.

## 수행평가 틀과 반별 회차

공통 문제를 한 번 정의하고 반별 PIN·시작·제출·채점을 분리하는 API는 [docs/ASSESSMENT_CLASSES.md](docs/ASSESSMENT_CLASSES.md)를 참고하세요. 이 변경은 로컬 커밋에 포함되어 있으며 아직 푸시·운영 배포하지 않았습니다.

## 수행평가 공동 편집

[공유 API 명세](docs/ASSESSMENT_COLLABORATION.md): 교사 이메일과 `role: "EDITOR"`로 공유하고, `scope=mine|shared|all`로 목록을 조회합니다. 응답의 `owner.name`, `myRole`, `collaboratorCount`, `shared`로 카드 표시를 구성할 수 있습니다. EDITOR는 본인의 공유에서 나갈 수 있으며, 평가 삭제와 타인 공유 관리는 OWNER만 가능합니다. 공동 수정 시 PUT에 GET 응답의 `revision`을 보내면 오래된 버전은 409로 거절됩니다. 기존 PIN·QR·반 운영·채점·공유 알림도 EDITOR에게 제공됩니다. Postman의 ‘수행평가 공동 편집’ 폴더에서 요청 예시를 확인하세요.

## 약관·개인정보 처리방침 동의

[동의 API 명세](docs/CONSENTS.md): 교사 로그인 후 `GET /api/users/me/consents`로 현재 버전과 동의 상태를 조회하고, `POST`에 두 동의 값과 버전을 보내 DB에 저장합니다. `/history`로 본인의 과거 동의 이력을 확인할 수 있습니다. 문서 버전은 `LIT_TERMS_VERSION`·`LIT_PRIVACY_VERSION`(각 기본 v1)로 설정하며 실제 프런트 문서와 맞춰야 합니다. 같은 버전의 재요청은 최초 동의 시각을 유지합니다. 기존 가입·로그인 및 학생 PIN 참여 흐름은 바꾸지 않으며 회원가입 시 자동 동의 처리도 하지 않습니다.

문항별 임시저장과 다시 불러오기 요청·응답 및 충돌 처리는 [문항별 임시저장 API](docs/ANSWER_DRAFTS.md)를 참고하세요. 기존 PUT 답안 저장을 사용하며 GET 같은 경로로 복원합니다.

### AI 활용 과정 평가 V1

행동 근거 기반 Gemini 수준 추천·서버 인용 검증·100점 환산·교사 검토/확정은 `/api/teacher/evaluations/{id}/process-grading` API로 제공합니다. 평가 시작 전에 `/policy`로 목표·허용 규칙·문항 배점 비율·AI 미관측 정책을 설정해야 합니다. 제출 시 입력을 고정해 자동 예약하며, 정책 누락·근거 부족은0점 대신 판단 보류합니다. 투명성은 평가 전체에서 한 번 반영하며 재분석·교사 보정은 이력을 보존합니다. 기존 키워드 채점 API와 리포트는 호환 유지되고 Web 연결은 별도입니다. [정책·분석·근거·검토·확정 명세](docs/PROCESS_GRADING.md)를 참고하세요.

## 이메일 변경

[API 명세](docs/EMAIL_CHANGE.md)

로그인한 본인의 새 이메일에 인증번호를 보내 확인한 뒤 변경합니다. 기존 가입 인증번호·가입 토큰은 사용할 수 없습니다. username·비밀번호·현재 로그인 세션은 유지합니다.

## 회원탈퇴

[API 명세](docs/ACCOUNT_DELETION.md)

`DELETE /api/users/me`로 현재 비밀번호를 확인한 뒤 탈퇴하고 본인 소유 수행평가 틀·반별 회차·독립 평가 및 학생 답안·대화·이벤트·제출·채점·리포트·관련 알림을 모두 삭제합니다. 공유 편집자도 삭제된 평가에 접근할 수 없습니다.

## 비밀번호 변경

[API 명세](docs/PASSWORD_CHANGE.md)

`POST /api/users/me/password`로 현재 비밀번호를 확인한 후 새 비밀번호로 변경합니다. 기존 경로·응답은 유지합니다. 비밀번호 해시 갱신과 다른 기기의 Access/Refresh 세션 폐기를 한 트랜잭션으로 처리합니다.
