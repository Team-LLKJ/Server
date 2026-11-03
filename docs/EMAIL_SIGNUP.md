# 이메일 인증 회원가입 · 아이디 중복 확인

이메일 인증만 요구하며 학교·교사 초대 코드·교사 확인·관리자 승인은 요구하지 않습니다. 이메일 인증 성공 후 생성되는 `TEACHER`는 평가 운영 기능 역할이며 교사 신분을 확인했다는 뜻이 아닙니다. 학생의 비회원 PIN 참여와 기존 계정 로그인·갱신은 그대로 유지됩니다.

## API 흐름 (공개 API, 교사 토큰 불필요)

| 메서드 | 경로 | 요청 | 성공 응답 |
|---|---|---|---|
| GET | `/api/auth/username-availability?username=teacher01` | 아이디 query | 200 `{ "username":"teacher01", "available":true }` |
| POST | `/api/auth/email-verifications` | `{ "email":"teacher@example.com" }` | 200 `{ "expiresAt":"2026-11-02T10:10:00Z", "resendAfterSeconds":60 }` |
| POST | `/api/auth/email-verifications/confirm` | `{ "email":"teacher@example.com", "code":"012345" }` | 200 `{ "emailVerificationToken":"43자리 토큰", "expiresAt":"2026-11-02T10:15:00Z" }` |
| POST | `/api/auth/signup/teacher` | 아래 가입 요청 | 201 기존 User |

```json
{
  "username": "teacher01",
  "password": "Example-password-123",
  "email": "teacher@example.com",
  "name": "이용자",
  "emailVerificationToken": "이메일 인증 확인 응답의 43자리 토큰"
}
```

가입 성공 후 기존 `/api/auth/login`으로 로그인합니다. 약관 동의는 [CONSENTS.md](CONSENTS.md)의 로그인 후 별도 API이며 자동 동의하지 않습니다.

## 동작·검증

- 아이디는 영문·숫자·밑줄 4~30자이며 소문자 정규화합니다. 중복 확인 후 다른 사람이 가입할 수 있으므로 가입 시 DB UNIQUE 검사도 유지합니다.
- 이메일은 유효한 형식·최대254자, 소문자 정규화합니다. 인증한 이메일과 가입 이메일이 같아야 합니다.
- 인증번호는 6자리 문자열(앞자리0 유지), 10분 유효, 이메일당 재발송 간격60초, 확인 실패5회 후 해당 번호 사용 중지입니다. 틀린 횟수는 요청 실패에도 유지됩니다.
- 재발송 성공 시 이전 번호와 가입용 인증 토큰은 무효화됩니다. SMTP 발송 실패 시 이전 기록은 유지합니다.
- 확인 성공 시 256비트 난수 가입용 토큰(43자리 URL-safe Base64)을 반환합니다. 15분 유효·일회용이며 원래 인증번호는 다시 확인할 수 없습니다. 확인 응답을 잃으면 재발송부터 진행합니다.
- 가입과 인증 토큰 소비는 같은 트랜잭션입니다. 중복 아이디 등으로 가입 실패 시 토큰 소비도 롤백됩니다. 동시 가입은 한 요청만 성공합니다.
- IP당 인증메일 발송·번호 확인·아이디 조회는 각30회/분, 가입20회/분입니다. 이메일 재발송60초 제한도 별도로 적용됩니다. 현재 단일 서버 메모리 IP 제한 방식입니다.
- DB에는 인증번호의 PBKDF2 해시와 가입 토큰의 SHA-256 해시만 저장합니다. 이메일·만료·확인·소비 시각도 저장하며 원문 번호·토큰은 API 로그에 출력하지 않습니다.
- 응답에 인증번호를 반환하거나 개발용 인증 우회 경로를 제공하지 않습니다. SMTP 설정이 없으면 명확히503으로 실패합니다.
- 기존 DB의 학교 컬럼과 초대 테이블은 기존 데이터 보존·응답 호환용입니다. 신규 가입자의 school은 빈 문자열이며 수집하지 않습니다. 사용자가 이전 school/inviteCode를 보내더라도 이메일 인증을 대신할 수 없습니다.
- 기존 로그인 사용자에게 소급 이메일 인증을 강제하지 않습니다. `/api/auth/teacher-invitations`는 로그인한 사용자에게410 `TEACHER_INVITATIONS_DISABLED`를 반환합니다. 과거 bootstrap 초대 환경변수도 더 이상 사용하지 않습니다.

## 실제 발송 설정

EC2 `/opt/lit/app.env` 또는 로컬 환경에 설정합니다. 실제 비밀값을 Git·프런트 VITE_ 변수·채팅에 넣지 마세요.

```dotenv
SMTP_HOST=메일서비스가안내한호스트
SMTP_PORT=587
SMTP_USERNAME=메일서비스가발급한계정
SMTP_PASSWORD=메일서비스가발급한비밀번호
LIT_MAIL_FROM=인증된발신이메일주소
```

현재 전송 설정은 인증된 STARTTLS SMTP(587)입니다. 제공자의 발신자 승인·수신 허용·발송 한도도 확인해야 합니다. 연결·읽기·쓰기 타임아웃은 각각5초입니다. 변경 후 백엔드 재시작이 필요합니다. SMTP 설정과 실제 수신함 테스트가 끝나기 전에는 실메일 발송 성공을 보장하지 않습니다.

## 오류

| HTTP | code | 처리 |
|---|---|---|
| 400 | INVALID_REQUEST | 아이디·이메일 형식, 인증번호6자리, 필수 필드 확인 |
| 400 | INVALID_EMAIL_CODE | 코드 재입력(실패 횟수 누적) |
| 400 | EMAIL_CODE_EXPIRED | 미발송·만료·이미 확인한 번호: 새 메일 요청 |
| 400 | EMAIL_VERIFICATION_REQUIRED | 가입 토큰 누락·이메일 불일치·만료·소비: 이메일 재인증 |
| 409 | EMAIL_ALREADY_EXISTS | 이미 등록된 이메일: 로그인 사용 |
| 409 | ACCOUNT_ALREADY_EXISTS | 가입 시 중복 아이디 또는 이메일 |
| 410 | TEACHER_INVITATIONS_DISABLED | 초대 코드 가입 종료: 이메일 인증 사용 |
| 429 | EMAIL_RESEND_LIMITED | 같은 이메일은60초 대기 |
| 429 | EMAIL_ATTEMPTS_EXCEEDED | 인증번호 확인 실패5회: 재발송 |
| 429 | RATE_LIMITED | IP 호출 제한: 잠시 후 재시도 |
| 502 | EMAIL_DELIVERY_FAILED | SMTP 오류: 발송 설정 점검 후 재시도 |
| 503 | EMAIL_NOT_CONFIGURED | SMTP 및 발신 주소 설정 필요 |
