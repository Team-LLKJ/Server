# 교사 Auth · 학생 비회원 참여

이메일 인증을 완료한 사용자가 교사 기능 계정으로 가입·로그인합니다. 학교·초대 코드·교사 신분 확인·승인 절차는 없습니다. 학생은 계정 없이 PIN 확인 후 닉네임으로 평가에 참여합니다.
교사의 계정 인증 토큰과 학생의 평가별 참가자 토큰은 서로 대체할 수 없습니다.

## 교사 API

기본 경로 `/api/auth`. 모든 JSON 응답은 `Cache-Control: no-store`입니다.

| 메서드 | 경로 | 인증 | 성공 |
|---|---|---|---|
| POST | `/signup/teacher` | 유효한 이메일 인증 토큰 | 201 User |
| POST | `/login` | 없음 | 200 Tokens |
| POST | `/refresh` | Refresh Token(Body) | 200 Tokens |
| GET | `/me` | 교사 Access Token | 200 User |
| POST | `/logout` | 교사 Access Token | 200 Message |
| POST | `/email-verifications` | 없음 | 200 EmailVerificationResponse |
| POST | `/email-verifications/confirm` | 없음 | 200 EmailConfirmationResponse |
| GET | `/username-availability?username=...` | 없음 | 200 UsernameAvailabilityResponse |
| POST | `/teacher-invitations` | 교사 Access Token | 410 TEACHER_INVITATIONS_DISABLED |

기존 `/signup/student`는 계정을 만들지 않고 410 STUDENT_ACCOUNTS_DISABLED를 반환합니다.
이전 버전 DB의 STUDENT 계정도 로그인·갱신·Access Token 인증을 허용하지 않습니다. 기존 행을 삭제하지는 않습니다.

교사 가입:

```json
{"username":"teacher01","password":"Example-password-123","email":"teacher@example.com","name":"교사","emailVerificationToken":"43자리 가입용 인증 토큰"}
```

- username: 영문·숫자·밑줄 4~30자, 소문자 정규화·중복 불가.
- password는 8~20자이며 공백만 입력할 수 없습니다. PBKDF2 해시로 저장합니다.
- email: 이메일 형식·최대254자·소문자 정규화·중복 불가.
- name: 공백 불가·최대50자.
- emailVerificationToken: 인증번호 확인 API로 발급받은 43자리 토큰, 해당 이메일에만 유효·15분·일회용.
- 학교·초대 코드는 요청에서 제거됐습니다. 기존 DB의 학교 및 초대 기록은 보존하지만 가입에 사용하지 않습니다. 기존 User 응답의 school은 호환을 위해 남고 신규 가입자는 빈 문자열입니다.
- 이메일 인증은 이메일 소유만 확인합니다. TEACHER 역할은 교사 신분 확인을 의미하지 않으며 별도 승인은 없습니다.
- 인증번호 발송·확인·재시도·SMTP 설정은 [이메일 가입 명세](EMAIL_SIGNUP.md)를 참고하세요.

로그인 Body: `{"username":"teacher01","password":"Example-password-123"}`.
응답:

```json
{"tokenType":"Bearer","accessToken":"43자리 토큰","accessExpiresAt":"2026-10-26T14:00:00Z","refreshToken":"43자리 토큰","refreshExpiresAt":"2026-11-09T12:00:00Z","user":{"id":"uuid","username":"teacher01","email":"teacher@example.com","name":"교사","school":"테스트학교","role":"TEACHER","status":"ACTIVE","grade":null,"classNumber":null,"studentNumber":null,"createdAt":"2026-10-26T12:00:00Z"}}
```

- 교사 보호 API: `Authorization: Bearer {accessToken}`.
- `/api/teacher/evaluations` 생성·설정·조회·시작·종료·학생 기록 조회는 로그인한 교사 및 평가 소유권을 검사.
- Access Token 2시간, Refresh Token 최초 로그인 기준 최대14일의 opaque 난수 토큰. JWT가 아니며 DB에는 해시만 저장.
- 신규 로그인·갱신 시 발급하는 Access Token부터 적용하며, 기존 토큰의 만료 시간은 유지합니다. Refresh Token 만료까지 2시간 미만이면 Access Token도 해당 시각에 만료됩니다.
- 갱신 Body: `{"refreshToken":"발급받은 토큰"}`. 갱신 시 두 토큰 모두 교체, 이전 토큰 폐기. 이미 사용한 Refresh Token 재사용 시 해당 로그인 세션 계열 전체 폐기.
- 로그아웃은 현재 세션 계열만 폐기. 로그인 실패5회 시 계정15분 제한.
- 학생 참가자 토큰으로 수업 생성이나 교사 API 호출 불가.

## 학생 비회원 참여

```http
GET /api/participation/verify?pin=012345
POST /api/participation/join
Content-Type: application/json
```

가입·로그인·이메일·학교 정보 없이 참여:

```json
{"pin":"012345","nickname":"학생1"}
```

PIN은 앞자리0을 유지하는6자리 문자열. 닉네임은 앞뒤 공백 제거 후1~20자, 평가 내 중복 불가.
성공 응답:

```json
{"participantId":"uuid","participantToken":"64자리 참가자 토큰","lobby":{"evaluationId":"uuid","title":"수학 평가","state":"WAITING","screen":"WAITING","participantCount":1,"participants":[{"id":"uuid","nickname":"학생1","presence":"CONNECTED"}]}}
```

이후 학생 상태·문항·답안·AI·대화·제출 API(`/api/evaluations/{id}/...`)에는 다음 헤더를 사용합니다.

```http
X-Participant-Token: {participantToken}
```

다른 평가의 토큰·다른 학생의 토큰으로 본인 기록을 대신 조회할 수 없으며, 토큰은 해당 참가자의 비밀 인증 정보입니다.
새 참가자 토큰은256비트 난수의64자리hex 문자열이며 DB에는 SHA-256 해시를 저장합니다.
이전 버전에서 발급된 동일 형식의 토큰은 호환 처리하되 새 토큰을 발급하는 방식으로 임의 재귀속하지 않습니다.
학생 API는 교사 Authorization 헤더 대신 참가자 토큰으로 인증합니다. PIN 확인·참여는 계정 인증을 요구하지 않습니다.

재접속:

```json
{"pin":"012345","participantToken":"기존 참가자 토큰"}
```

같은 참가자 ID·닉네임·답안 유지, 이탈 후 active 상태 복구. 재접속 시 nickname은 무시합니다.
평가 시작 후 신규 참여는 차단하고 기존 토큰 재접속만 허용합니다.
토큰을 잃은 경우 닉네임만으로 기존 기록을 복구하지 않습니다. 재접속할 클라이언트가 토큰을 보관해야 합니다.

비밀번호 찾기·재설정은 [PASSWORD_RESET.md](PASSWORD_RESET.md)에 정의합니다. `POST /api/auth/password-reset/request`(이메일), `/confirm`(token·newPassword)은 비로그인 공개 API입니다. 성공하면 모든 로그인 세션이 폐기됩니다. 신규 회원가입 인증을 위해서는 SMTP 메일 발송 설정이 필요합니다.

## 오류

일반 형식: `{"code":"AUTH_REQUIRED","message":"로그인이 필요합니다."}`.

| HTTP | code | 원인 |
|---|---|---|
| 400 | INVALID_REQUEST / EMAIL_VERIFICATION_REQUIRED | 입력 오류 또는 이메일 인증 미완료·만료·재사용 |
| 401 | AUTH_REQUIRED / INVALID_ACCESS_TOKEN | 교사 로그인 필요·토큰 오류 |
| 401 | INVALID_CREDENTIALS | 로그인 실패·제한·비활성·STUDENT 계정 |
| 401 | INVALID_REFRESH_TOKEN / REFRESH_TOKEN_REUSED | 교사 갱신 토큰 오류·재사용 |
| 401 | PARTICIPANT_TOKEN_REQUIRED / INVALID_PARTICIPANT_TOKEN | 학생 참가자 토큰 누락·다른 평가·이탈·잘못된 토큰 |
| 403 | ACCESS_DENIED | 교사 권한 부족 |
| 404 | EVALUATION_NOT_FOUND | 평가 없음·다른 교사 소유 평가 |
| 409 | ACCOUNT_ALREADY_EXISTS / NICKNAME_TAKEN | 교사 계정 중복·참여 닉네임 중복 |
| 410 | STUDENT_ACCOUNTS_DISABLED | 학생 가입 경로 비활성화 |
| 429 | RATE_LIMITED | 요청 횟수 제한 |

기존 DB의 사용자·평가·답안 기록은 삭제하지 않습니다. 소유자가 없는 평가를 새 교사에게 자동 귀속하지 않습니다.

이메일 인증 상세 오류와 요청·응답: [EMAIL_SIGNUP.md](EMAIL_SIGNUP.md).
