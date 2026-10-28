# LIT Server

Spring Boot 3.5.16 / Java 17+ 기반 평가 참여·풀이·제출 및 교사 Auth·학생 비회원 참여 API입니다.
화면 UI와 채점 기능은 포함하지 않습니다.

## 로컬 실행

Java 17 이상이 필요하며 Maven Wrapper가 포함되어 있습니다.

```sh
cd /Users/dgsw10/Desktop/LIT
export LIT_AI_PROVIDER='stub'
export LIT_BOOTSTRAP_SCHOOL='테스트학교'
export LIT_BOOTSTRAP_TEACHER_INVITE="$(openssl rand -hex 24)"
# 아래 값을 Postman의 bootstrapInviteCode 변수에 입력하세요. 초대 코드는 외부에 공개하지 마세요.
printf '%s\n' "$LIT_BOOTSTRAP_TEACHER_INVITE"
./mvnw spring-boot:run
```

API 주소는 `http://localhost:5174`입니다. 부트스트랩 초대 코드는 24시간 유효한 일회용 코드입니다.
최초 교사 가입에만 사용하며, 이후에는 로그인한 교사가 `/api/auth/teacher-invitations`로 초대 코드를 발급할 수 있습니다.
초기 코드가 필요 없다면 부트스트랩 환경변수를 생략해도 서버는 실행됩니다.
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

교사만 일회용 학교별 초대 코드로 가입합니다.
교사는 `/api/auth/login`으로 로그인하고 교사용 API에 다음 헤더를 사용합니다.

```http
Authorization: Bearer {accessToken}
```

학생은 가입·로그인 없이 PIN과 닉네임으로 참여합니다. 참여 응답의 participantToken을 학생 API의 `X-Participant-Token` 헤더에 사용하세요.
교사 공통 키는 사용하지 않습니다.
Access Token은 15분, Refresh Token은 최초 로그인 기준 최대 14일이며 회전·재사용 감지·로그아웃을 지원합니다.
토큰은 opaque 난수 방식이며 DB에는 해시만 저장합니다. 역할은 DB에서 확인합니다.

[Auth 상세 명세](docs/AUTH.md)에 가입 Body, 토큰 응답, 초대·오류 정책을 정리했습니다.

## API 목록

### Auth

| 메서드 | 경로 | 기능 |
|---|---|---|
| POST | `/api/auth/signup/teacher` | 교사 초대 코드 가입 |
| POST | `/api/auth/login` | 공통 로그인 |
| POST | `/api/auth/refresh` | 토큰 교체 |
| GET | `/api/auth/me` | 본인 계정 |
| POST | `/api/auth/logout` | 현재 로그인 세션 폐기 |
| POST | `/api/auth/teacher-invitations` | 같은 학교 교사 초대 코드 발급 |

### 평가·참여

교사용 `/api/teacher/**`는 교사 로그인 및 평가 소유권을 검사합니다.
PIN 확인·참여는 공개입니다. 학생 풀이 API는 로그인 대신 평가별 참가자 토큰이 필요합니다.

| 메서드 | 경로 | 기능 |
|---|---|---|
| POST | `/api/teacher/evaluations` | 평가 생성 |
| GET | `/api/teacher/evaluations/{id}` | 평가 정보·PIN |
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
| PUT | `/api/evaluations/{id}/questions/{questionId}/answer` | 답안 저장·수정 |
| POST | `/api/evaluations/{id}/questions/{questionId}/ai/questions` | AI 질문 |
| GET | `/api/evaluations/{id}/conversations` | 본인 대화 |
| GET | `/api/evaluations/{id}/learning-events` | 본인 학습 기록 |
| GET | `/api/evaluations/{id}/submission-preview` | 제출 내용 확인 |
| POST | `/api/evaluations/{id}/submission` | 최종 제출 확정 |
| GET | `/api/evaluations/{id}/submission` | 확정된 기록 조회 |

평가 생성: `{"title":"수학 평가","expiresInMinutes":60}`. 제목 1~100자, 유효 시간 1~1440분.
문항 설정: `{"aiPolicy":"HINT_ONLY","questions":[{"position":1,"title":"문항1","content":"2+2는?"}]}`.
문항 1~100개, 양수 순서 중복 불가. 제목 최대 200자, 본문 최대 10,000자.
참여: `{"pin":"012345","nickname":"학생1"}`. PIN은 6자리 문자열, 닉네임은 1~20자.
참여 응답은 participantId·participantToken·lobby를 반환합니다.
재접속은 `{"pin":"012345","participantToken":"기존 토큰"}`으로 호출하며 참가자 ID·답안·닉네임을 유지합니다.

## 답안·AI·제출 정책

- 답안 저장: `{"content":"답안","expectedVersion":0,"source":"DIRECT"}`.
- content 최대 10,000자, 빈 답안 허용. 현재 버전이 일치해야 저장되며 성공 시 version +1.
- source는 DIRECT / AI_REFERENCED / AI_USED. AI 참고·사용은 같은 참가자·문항의 완료된 aiRequestId 필요.
- 문항 전환 전 저장 성공을 확인하세요. 프런트의 미저장 입력 보존·실패 표시는 별도 구현 항목입니다.
- AI 정책은 DISABLED / HINT_ONLY / FULL. HINT_ONLY는 모델에 지시하는 제한으로 정답 미노출을 보장하지는 않습니다.
- AI 질문: `{"requestId":"ask-001","text":"힌트를 주세요"}`. text 최대 4,000자.
- requestId는 `[A-Za-z0-9_-]{1,64}`. 같은 요청 재시도는 같은 ID·본문, 새 질문은 새 ID.
- 참가자당 동시 1건·분당 10건·평가당 100건. 실패도 기록되며 자동 재호출하지 않습니다.
- AI 상태 PENDING / COMPLETED / FAILED. 진행 중 중복 요청은 202, 완료는 200, 제공자 실패는 502/503/504 및 Turn.errorCode.
- 대화·학습 기록은 `afterId=0&limit=100` 커서 조회. limit은 1~200. 대화에는 선택적 questionId 필터가 있습니다.
- 제출은 미리 확인 응답의 reviewToken을 `{"reviewToken":"..."}`으로 전달합니다. 내용 변경 시 409 REVIEW_CHANGED.
- AI 진행 중에는 제출 불가. 빈 답안은 확인 후 제출 가능. 제출 후 답안·대화는 읽기 전용입니다.
- 평가 state는 WAITING / STARTED / ENDED. 학생 screen은 WAITING / SOLVING / SUBMITTED / ENDED / EXPIRED.
- 연결 갱신이 30초 없으면 DISCONNECTED. 상태 조회를 3~5초 간격, 별도 heartbeat는 10초 간격으로 호출하세요.

## Postman

`postman/LIT.postman_collection.json`을 Import하고 `bootstrapInviteCode`에 최초 교사 초대 코드를 입력하세요.
**전체 흐름 (테스트)** 폴더를 순서대로 실행하면 교사 가입·로그인·학생 비회원 참여·평가·답안·AI·제출·교사 토큰 갱신·로그아웃까지 진행합니다.
계정 이름·비밀번호는 첫 요청에서 테스트용으로 생성하고 토큰·ID는 응답에서 자동 저장합니다.
가입 API는 일회용 초대 코드를 소비하므로 전체 재실행에는 새 초대 코드가 필요합니다.
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

인증·역할·소유권·초대 코드·갱신 재사용·로그아웃·계정 제한,
전체 평가 흐름·답안 충돌·AI 대화 격리·제출 고정·이전 스키마 보존을 자동 테스트합니다.

기술 참고: [Spring Security 구조](https://docs.spring.io/spring-security/reference/servlet/architecture.html),
[비밀번호 저장](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).

학생 회원가입은 비활성화되어 `/api/auth/signup/student` 호출 시 410 STUDENT_ACCOUNTS_DISABLED를 반환합니다. 기존 STUDENT 계정도 로그인·갱신을 허용하지 않습니다.

비밀번호 찾기·재설정 기능은 제외했습니다. SMTP나 메일 발송 환경변수는 필요하지 않습니다.
