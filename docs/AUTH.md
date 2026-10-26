# 교사 Auth · 학생 비회원 참여

교사만 회원가입·로그인합니다. 학생은 계정 없이 PIN 확인 후 닉네임으로 평가에 참여합니다.
교사의 계정 인증 토큰과 학생의 평가별 참가자 토큰은 서로 대체할 수 없습니다.

## 교사 API

기본 경로 `/api/auth`. 모든 JSON 응답은 `Cache-Control: no-store`입니다.

| 메서드 | 경로 | 인증 | 성공 |
|---|---|---|---|
| POST | `/signup/teacher` | 유효한 교사 초대 코드 | 201 User |
| POST | `/login` | 없음 | 200 Tokens |
| POST | `/refresh` | Refresh Token(Body) | 200 Tokens |
| GET | `/me` | 교사 Access Token | 200 User |
| POST | `/logout` | 교사 Access Token | 200 Message |
| POST | `/teacher-invitations` | 교사 Access Token | 201 Invitation |
| POST | `/password-reset/request` | 없음 | 202 Message |
| POST | `/password-reset/confirm` | 이메일 재설정 토큰 | 200 Message |

기존 `/signup/student`는 계정을 만들지 않고 410 STUDENT_ACCOUNTS_DISABLED를 반환합니다.
이전 버전 DB의 STUDENT 계정도 로그인·갱신·Access Token 인증을 허용하지 않습니다. 기존 행을 삭제하지는 않습니다.

교사 가입:

```json
{"username":"teacher01","password":"Example-password-123","email":"teacher@example.com","name":"교사","school":"테스트학교","inviteCode":"발급받은 교사 초대 코드"}
```

- username: 영문·숫자·밑줄 4~30자, 소문자 정규화·중복 불가.
- password: 공백 불가·10~128자, PBKDF2 해시로 저장.
- email: 이메일 형식·최대254자·소문자 정규화·중복 불가.
- name: 공백 불가·최대50자. school: 공백 불가·최대100자.
- inviteCode: 32~128자, 학교 일치·만료 전·미사용 코드 필요.
- 교사 초대 발급 Body: `{"expiresInHours":24}`. 1~168시간, 발급 교사의 학교로 고정.
- 첫 코드는 LIT_BOOTSTRAP_TEACHER_INVITE와 LIT_BOOTSTRAP_SCHOOL로 등록하며 24시간 유효. 같은 코드로 재시작해도 소비·만료 상태가 초기화되지 않음.

로그인 Body: `{"username":"teacher01","password":"Example-password-123"}`.
응답:

```json
{"tokenType":"Bearer","accessToken":"43자리 토큰","accessExpiresAt":"2026-10-26T12:15:00Z","refreshToken":"43자리 토큰","refreshExpiresAt":"2026-11-09T12:00:00Z","user":{"id":"uuid","username":"teacher01","email":"teacher@example.com","name":"교사","school":"테스트학교","role":"TEACHER","status":"ACTIVE","grade":null,"classNumber":null,"studentNumber":null,"createdAt":"2026-10-26T12:00:00Z"}}
```

- 교사 보호 API: `Authorization: Bearer {accessToken}`.
- `/api/teacher/evaluations` 생성·설정·조회·시작·종료·학생 기록 조회는 로그인한 교사 및 평가 소유권을 검사.
- Access Token 15분, Refresh Token 최초 로그인 기준 최대14일의 opaque 난수 토큰. JWT가 아니며 DB에는 해시만 저장.
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

## 교사 비밀번호 재설정

요청: `{"email":"teacher@example.com"}`. 가입 여부와 무관하게 같은202 안내를 반환하며 토큰을 응답하지 않습니다.
메일 설정 누락은 모든 요청에503 MAIL_NOT_CONFIGURED. SMTP 발송 실패는 DB를 롤백하고 같은 안내 응답을 유지합니다.
메일 링크 `${LIT_RESET_BASE_URL}#token=...`에서 프런트가 토큰을 읽어 다음 Body로 확인합니다.

```json
{"token":"43자리 이메일 재설정 토큰","newPassword":"New-example-password-456"}
```

토큰은30분·일회용·해시 저장. 성공 시 해당 교사의 모든 로그인 세션·미사용 재설정 토큰을 폐기합니다.
SMTP_HOST/PORT/USERNAME/PASSWORD와 LIT_MAIL_FROM 설정 필요. 가입 이메일 인증 및 UI는 별도 구현 범위입니다.

## 오류

일반 형식: `{"code":"AUTH_REQUIRED","message":"로그인이 필요합니다."}`.

| HTTP | code | 원인 |
|---|---|---|
| 400 | INVALID_REQUEST / INVALID_INVITE / INVALID_RESET_TOKEN | 입력·교사초대·재설정 토큰 오류 |
| 401 | AUTH_REQUIRED / INVALID_ACCESS_TOKEN | 교사 로그인 필요·토큰 오류 |
| 401 | INVALID_CREDENTIALS | 로그인 실패·제한·비활성·STUDENT 계정 |
| 401 | INVALID_REFRESH_TOKEN / REFRESH_TOKEN_REUSED | 교사 갱신 토큰 오류·재사용 |
| 401 | PARTICIPANT_TOKEN_REQUIRED / INVALID_PARTICIPANT_TOKEN | 학생 참가자 토큰 누락·다른 평가·이탈·잘못된 토큰 |
| 403 | ACCESS_DENIED | 교사 권한 부족 |
| 404 | EVALUATION_NOT_FOUND | 평가 없음·다른 교사 소유 평가 |
| 409 | ACCOUNT_ALREADY_EXISTS / NICKNAME_TAKEN | 교사 계정 중복·참여 닉네임 중복 |
| 410 | STUDENT_ACCOUNTS_DISABLED | 학생 가입 경로 비활성화 |
| 429 | RATE_LIMITED | 요청 횟수 제한 |
| 503 | MAIL_NOT_CONFIGURED | 교사 재설정 메일 설정 누락 |

기존 DB의 사용자·평가·답안 기록은 삭제하지 않습니다. 소유자가 없는 평가를 새 교사에게 자동 귀속하지 않습니다.
