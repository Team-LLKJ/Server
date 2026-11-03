# 이용약관·개인정보 처리방침 동의 API

교사 로그인 Access Token을 사용합니다. 학생 PIN 참여와 기존 회원가입·로그인·보안 설정은 변경하지 않습니다. 회원가입에 동의를 자동으로 붙이거나 기존 사용자를 동의한 것으로 처리하지 않습니다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/api/users/me/consents` | 서버의 현재 문서 버전과 본인의 버전별 동의 상태 |
| POST | `/api/users/me/consents` | 현재 이용약관·개인정보 처리방침에 각각 동의, 두 기록을 원자적으로 저장 |
| GET | `/api/users/me/consents/history` | 본인의 전체 동의 이력, 최신 시각순 |

인증 헤더: `Authorization: Bearer {teacherAccessToken}`. 성공은 모두 HTTP 200입니다. 다른 계정의 조회·저장이나 학생 토큰 인증은 지원하지 않습니다.

## 프런트 연결 순서

1. 회원가입 후 로그인하거나 기존 교사로 로그인합니다.
2. GET으로 `terms.version`, `privacy.version`, `requiresAcceptance`를 확인합니다.
3. 해당 버전의 실제 문서 내용을 각각 보여주고 사용자가 두 동의 항목을 직접 선택하도록 합니다.
4. 선택 결과와 조회한 버전을 POST합니다. 체크박스의 동의 값을 임의로 true 처리하지 않습니다.
5. 성공 응답을 확인합니다. 409이면 최신 버전과 문서 내용을 다시 보여주고 재동의를 받습니다.

기존 로그인과 수업 생성 API에 동의 여부를 강제하는 차단은 추가하지 않았습니다. 이 API는 로그인 후 동의 기록용이며 회원가입 트랜잭션에 포함되지 않습니다. IP·User-Agent·클라이언트 제공 시각은 수집하지 않습니다.

## POST 요청

```json
{
  "termsVersion": "v1",
  "termsAccepted": true,
  "privacyVersion": "v1",
  "privacyAccepted": true
}
```

버전은 필수 문자열(최대64자), 두 accepted 값은 필수 boolean입니다. 둘 중 하나가 false이면 400 `CONSENT_REQUIRED`이며 저장하지 않습니다. 서버 버전과 일치하지 않으면 409 `POLICY_VERSION_MISMATCH`이며 두 문서 모두 저장하지 않습니다. 계정 ID와 동의 시각은 요청에서 받지 않습니다.

## GET·POST 응답

```json
{
  "terms": {"version": "v1", "accepted": true, "acceptedAt": "2026-11-02T10:00:00Z"},
  "privacy": {"version": "v1", "accepted": true, "acceptedAt": "2026-11-02T10:00:00Z"},
  "requiresAcceptance": false
}
```

처음 조회하거나 문서 버전이 바뀌어 새 동의가 없으면 해당 항목은 `accepted: false`, `acceptedAt: null`입니다. 어느 하나라도 미동의면 requiresAcceptance=true입니다. 기존 버전의 동의 기록은 이력에 남습니다. 같은 현재 버전의 POST 재시도는 최초 acceptedAt을 유지하고 기록을 추가하지 않습니다.

## 이력 응답

```json
{
  "items": [
    {"policyType": "PRIVACY_POLICY", "version": "v1", "acceptedAt": "2026-11-02T10:00:00Z"},
    {"policyType": "TERMS_OF_SERVICE", "version": "v1", "acceptedAt": "2026-11-02T10:00:00Z"}
  ]
}
```

## 서버 설정과 저장

- `LIT_TERMS_VERSION`, `LIT_PRIVACY_VERSION`: 각각 기본 `v1`. 앞뒤 공백 없이 1~64자.
- 실제 공개 문서는 프런트가 제공하며 문서 작성·게시 API는 포함하지 않습니다. 실제 게시된 문서 버전과 위 서버 설정을 일치시켜야 합니다.
- 내용이 바뀌면 새로운 버전을 사용하고 과거 문서·버전의 연결을 보관해야 합니다. 같은 버전에 다른 문서 내용을 덮어쓰지 않습니다.
- `user_policy_consents`: user_id FK, policy_type, policy_version, accepted_at. `(user_id,policy_type,policy_version)` 기본키와 계정 잠금으로 중복·동시 요청을 방지합니다. 클라이언트 시계 대신 서버 UTC 시각으로 저장합니다.
- 회원가입 동시 저장, 비회원 학생·보호자 동의, 동의 철회·삭제 API, 문서 본문 보관은 이번 범위에 없습니다.

## 오류

| HTTP | code | 처리 |
|---|---|---|
| 400 | INVALID_REQUEST | 필수 필드·boolean·버전 길이 확인 |
| 400 | CONSENT_REQUIRED | 두 문서를 각각 확인하고 동의 |
| 401 | AUTH_REQUIRED / INVALID_ACCESS_TOKEN | 교사 로그인 또는 토큰 갱신 |
| 409 | POLICY_VERSION_MISMATCH | 최신 GET과 문서 확인 후 재동의 |
