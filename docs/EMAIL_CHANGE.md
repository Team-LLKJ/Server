# 이메일 변경

로그인한 본인의 새 이메일에 인증번호를 보내 확인한 뒤 변경합니다. 기존 가입 인증번호·가입 토큰은 사용할 수 없습니다. username·비밀번호·현재 로그인 세션은 유지합니다.

Bearer Access Token과 현재 비밀번호가 두 단계 모두 필수입니다.

| 메서드 | 경로 | 요청 | 성공 |
|---|---|---|---|
| POST | `/api/users/me/email/verifications` | `{ "email": "new@example.com", "currentPassword": "현재 비밀번호" }` | 200 `{ "expiresAt": "ISO-8601", "resendAfterSeconds": 60 }` |
| POST | `/api/users/me/email/confirm` | `{ "email": "new@example.com", "currentPassword": "현재 비밀번호", "code": "012345" }` | 200 기존 User (`email` 변경) |

이메일은 소문자로 정규화하고 최대 254자, 인증번호는 앞자리 0을 유지하는 6자리 문자열입니다. 발송은 계정당 60초 간격(주소 변경 시에도 동일), 각 경로 IP당 10회/분입니다. 번호는 10분 유효·5회 오류 후 사용 중지·성공 시 일회용 소비입니다. 인증번호 해시와 발급 시점의 비밀번호 해시를 저장하며 발급 이후 비밀번호 변경 시 인증번호는 무효입니다. 다른 계정의 번호나 기존 가입 인증은 사용할 수 없습니다. 재발송 성공 시 기존 번호가 무효이며 SMTP 실패 시 기존 번호를 보존합니다. 대표 색 #007BFF HTML과 일반 텍스트 메일을 제공합니다.

미인증 401, 형식 오류 400 INVALID_REQUEST, 현재 비밀번호 오류 400 INVALID_CURRENT_PASSWORD, 동일 주소 400 EMAIL_UNCHANGED, 중복 주소(확인 시 동시 선점 포함) 409 EMAIL_ALREADY_EXISTS, 미발급·만료·비밀번호 변경 400 EMAIL_CODE_EXPIRED, 불일치 400 INVALID_EMAIL_CODE, 제한 429 EMAIL_RESEND_LIMITED / EMAIL_ATTEMPTS_EXCEEDED / RATE_LIMITED. SMTP 미설정 503 EMAIL_NOT_CONFIGURED, 실패 502 EMAIL_DELIVERY_FAILED. 성공 후 이전·새 주소에 남아 있는 가입용 인증도 삭제합니다. 실제 메일 테스트에는 SMTP 설정이 필요합니다.
