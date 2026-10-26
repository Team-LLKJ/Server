# LIT Server

Spring Boot 3.5.16 / Java 17+ 기반 평가 참여·풀이·제출 및 교사·학생 Auth API입니다.
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

API 주소는 `http://localhost:8080`입니다. 부트스트랩 초대 코드는 24시간 유효한 일회용 코드입니다.
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

교사는 일회용 학교별 초대 코드로, 학생은 학교·학년·반·번호를 입력하여 가입합니다.
공통 `/api/auth/login`으로 로그인하고 모든 보호 API에 다음 헤더를 사용합니다.

```http
Authorization: Bearer {accessToken}
```

교사 공통 키와 `X-Participant-Token`은 더 이상 인증에 사용하지 않습니다.
Access Token은 15분, Refresh Token은 최초 로그인 기준 최대 14일이며 회전·재사용 감지·로그아웃을 지원합니다.
토큰은 opaque 난수 방식이며 DB에는 해시만 저장합니다. 역할은 DB에서 확인합니다.

[Auth 상세 명세](docs/AUTH.md)에 가입 Body, 토큰 응답, 초대·재설정·오류 정책을 정리했습니다.

## API 목록

### Auth

| 메서드 | 경로 | 기능 |
|---|---|---|
| POST | `/api/auth/signup/student` | 학생 가입 |
| POST | `/api/auth/signup/teacher` | 교사 초대 코드 가입 |
| POST | `/api/auth/login` | 공통 로그인 |
| POST | `/api/auth/refresh` | 토큰 교체 |
| GET | `/api/auth/me` | 본인 계정 |
| POST | `/api/auth/logout` | 현재 로그인 세션 폐기 |
| POST | `/api/auth/teacher-invitations` | 같은 학교 교사 초대 코드 발급 |
| POST | `/api/auth/password-reset/request` | 재설정 메일 요청 |
| POST | `/api/auth/password-reset/confirm` | 새 비밀번호 설정 |

### 평가·참여

교사용 `/api/teacher/**`는 교사 로그인 및 평가 소유권을 검사합니다.
PIN 확인은 공개이며 참여와 풀이 API는 학생 로그인이 필요합니다.

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
| POST | `/api/participation/join` | 로그인한 학생의 참여·재접속 |
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
같은 계정이 다시 참여하면 기존 참가자 ID·답안·닉네임을 유지합니다.

## 답안·AI·제출 정책

- 답안 저장: `{"content":"답안","expectedVersion":0,"source":"DIRECT"}`.
- content 최대 10,000자, 빈 답안 허용. 현재 버전이 일치해야 저장되며 성공 시 version +1.
- source는 DIRECT / AI_REFERENCED / AI_USED. AI 참고·사용은 같은 학생·문항의 완료된 aiRequestId 필요.
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
**전체 흐름 (테스트)** 폴더를 순서대로 실행하면 가입·로그인·평가·답안·AI·제출·갱신·로그아웃까지 진행합니다.
계정 이름·비밀번호는 첫 요청에서 테스트용으로 생성하고 토큰·ID는 응답에서 자동 저장합니다.
가입 API는 일회용 초대 코드를 소비하므로 전체 재실행에는 새 초대 코드가 필요합니다.
가입 이후의 개별 요청은 저장된 변수로 재실행할 수 있으나 답안 expectedVersion과 제출 상태를 확인하세요.
**비밀번호 재설정 (별도 실행)** 폴더는 SMTP 설정 후에만 실행하세요. 재설정 토큰은 이메일에서 확인해 변수에 입력합니다.

## 외부 서비스 설정

실제 AI: `LIT_AI_PROVIDER=openai`, `OPENAI_API_KEY`, `OPENAI_MODEL`을 서버에 설정합니다.
Auth 개발·테스트는 외부 AI 호출 없이 stub을 사용합니다.
`LIT_JOIN_BASE_URL` 기본값은 `http://localhost:3000/join`이며 참여 웹 페이지는 별도로 필요합니다.

재설정 메일: `SMTP_HOST`, `SMTP_PORT`(기본 587), `SMTP_USERNAME`, `SMTP_PASSWORD`, `LIT_MAIL_FROM`을 설정하세요.
SMTP_AUTH·SMTP_STARTTLS 기본 true. 연결·읽기·쓰기 제한은 각각 5초입니다.
`LIT_RESET_BASE_URL` 기본값은 `http://localhost:3000/reset-password`입니다.
실제 메일을 보내는 테스트는 수행하지 않습니다. 비밀번호 재설정 UI는 별도 구현해야 합니다.

## 검증

인증·역할·소유권·초대 코드·갱신 재사용·로그아웃·계정 제한·비밀번호 재설정,
전체 평가 흐름·답안 충돌·AI 대화 격리·제출 고정·이전 스키마 보존을 자동 테스트합니다.

기술 참고: [Spring Security 구조](https://docs.spring.io/spring-security/reference/servlet/architecture.html),
[비밀번호 저장](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
