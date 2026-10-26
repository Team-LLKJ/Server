# Auth API 명세

기본 경로 `/api/auth`. 요청·응답은 JSON이며 토큰을 담은 응답에는 `Cache-Control: no-store`를 적용합니다.
인증 헤더는 교사·학생 모두 `Authorization: Bearer {accessToken}`입니다.
서버가 계정 DB에서 역할·활성 상태를 확인합니다. 클라이언트가 role을 지정해 권한을 얻을 수 없습니다.

## API 목록

| 메서드 | 경로 | 인증 | 성공 |
|---|---|---|---|
| POST | `/signup/student` | 없음 | 201 User |
| POST | `/signup/teacher` | 없음·유효한 초대 코드 필요 | 201 User |
| POST | `/login` | 없음 | 200 Tokens |
| POST | `/refresh` | 없음·Refresh Token 필요 | 200 Tokens |
| GET | `/me` | 로그인 | 200 User |
| POST | `/logout` | 로그인 | 200 Message |
| POST | `/teacher-invitations` | 교사 | 201 Invitation |
| POST | `/password-reset/request` | 없음 | 202 Message |
| POST | `/password-reset/confirm` | 없음·재설정 토큰 필요 | 200 Message |

### 학생 가입

```json
{"username":"student01","password":"Example-password-123","email":"student@example.com","name":"학생","school":"테스트학교","grade":1,"classNumber":2,"studentNumber":3}
```

### 교사 가입

```json
{"username":"teacher01","password":"Example-password-123","email":"teacher@example.com","name":"교사","school":"테스트학교","inviteCode":"발급받은 교사 초대 코드"}
```

공통 규칙:
- username: 영문·숫자·밑줄 4~30자. 소문자로 정규화하며 중복 불가.
- password: 10~128자. 공백만 입력 불가. PBKDF2 해시로 저장.
- email: 필수·최대 254자·이메일 형식. 앞뒤 공백 제거·소문자 정규화·중복 불가.
- name: 공백 불가·최대 50자. school: 공백 불가·최대 100자.
- 학생 grade: 1~12, classNumber: 1~99, studentNumber: 1~999.
- 교사 inviteCode: 32~128자. 일회용·유효 시간 내·초대 코드 학교와 가입 학교 일치 필요.
- 가입 성공은 계정 생성만 수행. 로그인 API로 토큰을 발급받아야 함.
- 학교·학년·반·번호는 학생이 입력한 프로필이며 학교 구성원임을 별도로 인증하지는 않음.

User 응답:

```json
{"id":"uuid","username":"student01","email":"student@example.com","name":"학생","school":"테스트학교","role":"STUDENT","status":"ACTIVE","grade":1,"classNumber":2,"studentNumber":3,"createdAt":"2026-10-26T12:00:00Z"}
```

교사는 학년·반·번호가 null입니다. 비밀번호 해시와 인증 토큰 해시는 응답하지 않습니다.

### 로그인·갱신·로그아웃

```json
{"username":"student01","password":"Example-password-123"}
```

로그인과 갱신 응답:

```json
{"tokenType":"Bearer","accessToken":"43자리 토큰","accessExpiresAt":"2026-10-26T12:15:00Z","refreshToken":"43자리 토큰","refreshExpiresAt":"2026-11-09T12:00:00Z","user":{"id":"uuid","role":"STUDENT"}}
```

위 user는 실제로 전체 User 객체입니다. 예시 토큰·UUID는 설명용입니다.
토큰은 서명된 JWT가 아닌 256비트 난수의 opaque token입니다. JWT 디코딩으로 role을 판단하지 말고 `/me` 또는 Tokens.user를 사용하세요.
서버 DB에는 Access·Refresh Token 모두 SHA-256 해시만 저장합니다.

- Access Token: 15분. Refresh Token: 로그인부터 최대 14일.
- 갱신 요청: `{"refreshToken":"기존 Refresh Token"}`. Authorization 헤더 없이 요청합니다.
- 갱신 성공 시 Access·Refresh Token을 둘 다 교체하고 이전 토큰을 무효화합니다.
- 갱신해도 최초 로그인 기준 14일 기한이 연장되지는 않습니다.
- 이미 갱신에 사용한 Refresh Token이 다시 들어오면 해당 로그인 세션 계열 전체를 폐기합니다. 클라이언트는 갱신 요청을 동시에 보내지 말고 하나로 합쳐야 합니다.
- 로그아웃: Body 없음. 현재 Access Token이 속한 로그인 세션 계열만 폐기합니다.
- 만료된 Access Token으로 로그아웃할 수 없다면 갱신 후 로그아웃하거나 클라이언트의 토큰을 삭제하세요.
- 로그인 실패 5회 누적 시 해당 계정은 15분간 로그인이 제한됩니다. 실패 메시지는 존재하지 않는 계정과 동일합니다.
- 현재 HTTP 쿠키나 서버 세션으로 인증하지 않습니다. 브라우저 자동 전송 쿠키를 추가할 경우 CSRF 정책을 함께 변경해야 합니다.
- 운영에서는 HTTPS를 사용하고 프런트의 토큰 보관 방식을 별도로 결정하세요. CORS는 기본적으로 별도 허용하지 않습니다.

### 교사 초대 코드

`POST /teacher-invitations` Body: `{"expiresInHours":24}`. 1~168시간 허용.

```json
{"inviteCode":"43자리 일회용 초대 코드","school":"테스트학교","expiresAt":"2026-10-27T12:00:00Z"}
```

발급한 교사의 학교로 고정됩니다. 응답에서 코드를 한 번 전달하고 DB에는 해시만 보관합니다.
코드를 교사에게 안전하게 전달하세요. 관리자 역할은 따로 없으며, 확인된 교사가 같은 학교의 다른 교사를 초대하는 방식입니다.
최초 교사는 서버의 `LIT_BOOTSTRAP_TEACHER_INVITE`와 `LIT_BOOTSTRAP_SCHOOL`로 등록합니다.
부트스트랩 코드는 24시간 유효하며 같은 코드로 재시작해도 만료·사용 상태를 초기화하지 않습니다.
초기 가입 후 부트스트랩 환경변수는 제거할 수 있습니다.

### 비밀번호 재설정

요청 Body: `{"email":"student@example.com"}`.
가입 여부와 관계없이 202 및 동일 안내 메시지를 반환합니다. 계정별 메일 생성은 1분에 1회입니다.
발송 설정이 없다면 가입 여부를 조회하기 전에 503 MAIL_NOT_CONFIGURED를 반환합니다.
SMTP 발송 실패 시 내부 트랜잭션은 롤백하고 외부에는 같은 안내 응답을 유지합니다.
토큰을 API 응답·로그에 노출하지 않으며 이메일 링크에만 전달합니다.
링크는 `${LIT_RESET_BASE_URL}#token=...` 형식입니다. 프런트는 fragment의 token을 읽어 다음 확인 요청을 보내야 합니다.

확인 Body:

```json
{"token":"이메일로 받은 43자리 토큰","newPassword":"New-example-password-456"}
```

토큰은 30분 유효·일회용이며 DB에는 해시만 저장합니다.
변경 성공 시 해당 계정의 모든 로그인 세션과 미사용 재설정 토큰을 무효화하고 로그인 실패 제한을 초기화합니다.
계정 이메일 확인(가입 시 인증), 홈 UI, 비밀번호 재설정 프런트 페이지는 포함하지 않습니다.

## 오류

형식: `{"code":"INVALID_CREDENTIALS","message":"아이디 또는 비밀번호가 올바르지 않습니다."}`

| HTTP | code | 원인 |
|---|---|---|
| 400 | INVALID_REQUEST | JSON·필드 검증 실패 |
| 400 | INVALID_INVITE | 초대 코드 없음·만료·사용·학교 불일치 |
| 400 | INVALID_RESET_TOKEN | 잘못된·만료된·사용한 재설정 토큰 |
| 401 | AUTH_REQUIRED | 로그인 필요 |
| 401 | INVALID_CREDENTIALS | 로그인 실패·계정 제한·비활성 계정 |
| 401 | INVALID_ACCESS_TOKEN | Access Token 형식·만료·폐기 오류 |
| 401 | INVALID_REFRESH_TOKEN | Refresh Token 없음·만료·폐기 오류 |
| 401 | REFRESH_TOKEN_REUSED | 이미 회전된 Refresh Token 재사용, 세션 계열 폐기 |
| 401 | PARTICIPATION_REQUIRED | 현재 학생 계정이 평가에 참여하지 않았거나 이탈 상태 |
| 403 | ACCESS_DENIED | 역할 또는 계정 접근 권한 부족 |
| 404 | EVALUATION_NOT_FOUND | 평가 없음 또는 다른 교사/소유자 없는 기존 평가 |
| 409 | ACCOUNT_ALREADY_EXISTS | 정규화한 아이디 또는 이메일 중복 |
| 429 | RATE_LIMITED | IP 기반 요청 제한 |
| 503 | MAIL_NOT_CONFIGURED | SMTP 발송 설정 누락 |

IP당 분당 제한: 가입 20회(교사·학생 합산), 로그인 30회, 갱신 30회, 초대 발급 30회,
재설정 요청·확인 합산 5회, PIN 확인·참여 합산 30회.
현재 단일 서버 메모리 기반이며 재시작 시 초기화합니다. 프록시의 remoteAddr를 사용하므로 운영 게이트웨이 정책과 조정해야 합니다.

## 기존 평가 API 인증 변경

- 교사 공통 키와 학생 X-Participant-Token 방식은 제거했습니다. 모두 로그인 Access Token을 사용합니다.
- 평가 생성 시 teacher_id를 저장하며 해당 교사만 조회·설정·시작·종료·학생 기록 조회 가능합니다.
- 학생은 로그인 후 PIN+nickname으로 참여하며 평가당 계정 하나의 참가 기록을 유지합니다.
- 참여 응답은 `{"participantId":"uuid","lobby":{...}}`이며 참가자 토큰을 추가 발급하지 않습니다.
- 재접속도 같은 로그인 계정으로 `/api/participation/join`을 호출하면 됩니다. nickname은 기존 참가자에 대해 무시됩니다.
- 참가 기록의 내부 ID는 유지되므로 답안·AI 대화·제출 구조는 그대로입니다.
- 이전 버전의 소유자 없는 평가/참가 기록은 자동 귀속하지 않습니다. 명시적인 관리자 데이터 이관이 필요합니다.
