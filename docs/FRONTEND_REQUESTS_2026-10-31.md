> 작업 상태: 압축본을 검증·수정한 로컬 백엔드 초안입니다. 2026-11-01 사용자 승인으로 기능별 커밋·PR을 분리했으며 새 기능은 아직 배포하지 않았습니다. 프런트 코드는 포함되지 않았습니다.

# 프런트 요청 반영 결과 (2026-10-31, #18)

프런트 문서 "LIT 백엔드 API 정리"의 1~7장에 대한 백엔드 답변입니다. 채점 화면(2·7장)의 상세 계약은 [AI_GRADING.md](AI_GRADING.md)를 보세요.

## 1. 지금 프런트에서 쓰는 API — 확인 결과

표에 있는 경로는 모두 그대로 동작합니다. 아래 다섯 가지는 동작이 다르거나 새로 생겼습니다.

| API | 상태 |
|---|---|
| `POST /api/auth/signup/student` | **학생 가입 없음.** 항상 410 STUDENT_ACCOUNTS_DISABLED입니다. 학생은 PIN과 닉네임으로 참여합니다. 화면에서 학생 가입을 빼 주세요. |
| `POST /api/auth/password-reset/request`, `/confirm` | **2026-11-04 추가됨.** 이메일 링크 요청·새 비밀번호 확정을 지원합니다. 요청·응답·환경 설정은 [PASSWORD_RESET.md](PASSWORD_RESET.md)를 참고하세요. |
| `DELETE /api/teacher/evaluations/{id}` | **이번에 새로 추가.** 아래 3장 참고. |
| `PUT /api/teacher/evaluations/{id}/questions` | aiPolicy에 `EDIT_ASSIST`, `TASK_ASSIST` 두 값이 늘었고 `aiLevel`(1~5) 필드를 함께 받습니다. 기존 세 값은 그대로 됩니다. |
| CORS | 허용 메서드에 PATCH·DELETE를 추가했습니다. |

## 2. 채점 화면 API

모두 추가했습니다. [AI_GRADING.md](AI_GRADING.md) 1~7장.

| 요청 | 확정 경로 |
|---|---|
| 학생별 제출 현황 | `GET /api/teacher/evaluations/{id}/submissions` |
| 학생 답안 | `GET .../participants/{pid}/answers` |
| 학생 AI 대화 | `GET .../participants/{pid}/conversations` |
| AI 활용 분석 | `GET .../participants/{pid}/analysis` |
| 루브릭 기준 | `GET · PUT · DELETE .../rubric` |
| 루브릭 점수 | `GET .../participants/{pid}/rubric-scores`, `PUT .../rubric-scores/{criterionId}` |

## 3. 평가 수정과 종료 처리

### 평가 수정 `PATCH /api/teacher/evaluations/{id}`

```json
{ "title": "수정된 제목", "durationMinutes": 45, "expiresInMinutes": 60 }
```

- 세 필드 모두 선택이며 하나 이상 보내야 합니다. title 1~100자, durationMinutes(풀이 시간)·expiresInMinutes(지금부터 PIN 유효 시간) 1~1440.
- **WAITING에서만** 됩니다. 시작 후 409 EVALUATION_LOCKED. PIN이 만료된 대기 평가는 expiresInMinutes를 함께 보내야 합니다(아니면 410 PIN_EXPIRED).
- 응답: `{ id, title, pin, state, expiresAt, durationMinutes }`.
- 종료 시각은 시작 시점에 `시작 시각 + durationMinutes`로 정해지므로 "종료 시각" 대신 durationMinutes를 바꿉니다.

### 평가 삭제 `DELETE /api/teacher/evaluations/{id}`

- 204 No Content. 진행 중(STARTED이고 마감 전)이면 409 EVALUATION_IN_PROGRESS — 먼저 종료하세요.
- 참가자·답안·AI 대화·학습 이벤트·제출·리포트·채점·알림 기록을 함께 삭제하며 되돌릴 수 없습니다. 확인 모달을 꼭 띄워 주세요.

### 시간 만료 시 자동 종료 — 6장 3번 답

이미 동작합니다. 서버가 1초 간격으로 마감된 평가를 **`ENDED`** 로 바꾸고 미제출자의 최신 저장 답안을 자동 제출합니다(`submissionMode: AUTO_DEADLINE`).
`EXPIRED`는 **시작하지 않은 채 PIN이 만료된 대기 평가**의 `screen` 값에만 나옵니다. 진행한 평가의 `state`는 항상 ENDED입니다. 둘 다 "종료"로 보여 주는 현재 처리가 맞습니다.

### 종료 시 작성 중 답안 — 6장 4번 답

기존 서버 동작은 자동 제출 후 답안 고정입니다. 압축본의 종료 후 저장 유예 기능은 선택 설정으로만 유지합니다.

1. 교사 종료·시간 만료 시 서버가 미제출자의 마지막 저장본을 **자동 제출**합니다(기존).
2. 선택적으로 유예를 켠 경우에만 자동 제출된 학생의 마지막 저장을 받습니다. 기본0(비활성)입니다. 채점/리포트가 생성됐거나 기한이 지나면 거절합니다. 학생 화면이 종료를 감지하고 마지막 저장을 보내면 그대로 들어갑니다.

- 유예 시간은 `LIT_EVALUATION_END_GRACE_SECONDS`(기본 0(비활성), 별도 합의 후30 등으로 켤 수 있음)로 조정합니다.
- 유예 중 저장은 학습 이벤트에 `lateSave: true`로 남고, 제출본(snapshot)과 분석이 갱신됩니다. 제출 시각·submissionMode는 유지됩니다.
- 스스로 제출한(MANUAL) 학생과 유예 이후 요청은 기존대로 409 ALREADY_SUBMITTED입니다.
- 유예는 답안 저장에만 적용됩니다. AI 질문·수동 제출·이벤트 기록은 종료 즉시 막힙니다.

### 종료 알림

`GET /state` 폴링이면 충분합니다. 서버 state가 정확히 내려갑니다(ENDED, 제출된 학생은 screen=SUBMITTED). 별도 실시간 채널은 만들지 않았습니다.

## 4. 알림과 사용자 설정 — 6장 5번 답

**폴링**으로 가져오세요(예: 헤더 벨은 30초마다 `unread-count`). 알림 종류가 적고 실시간성이 크지 않아 SSE는 넣지 않았습니다.
알림은 교사 계정에만 있습니다(학생은 계정이 없음). 모두 교사 Bearer 토큰이 필요합니다.

| 요청 | 경로 | 응답 |
|---|---|---|
| 알림 목록 | `GET /api/notifications?page=0&size=20&unreadOnly=false` | `{ items, page, size, totalElements, totalPages, unreadCount }` |
| 하나 읽음 | `PATCH /api/notifications/{id}/read` | 알림 하나 |
| 하나 삭제 | `DELETE /api/notifications/{id}` | `{ deleted, unreadCount }` |
| 전체 삭제 | `DELETE /api/notifications` | `{ deleted, unreadCount }` |
| 모두 읽음 | `POST /api/notifications/read-all` | `{ updated, unreadCount }` |
| 읽지 않은 개수 | `GET /api/notifications/unread-count` | `{ unreadCount }` |
| 알림 설정 | `GET · PUT /api/users/me/settings` | `{ notify, sound, updatedAt }` |
| 내 정보 | `GET · PATCH /api/users/me` | 기존 `/api/auth/me`와 같은 User |
| 비밀번호 변경 | `POST /api/users/me/password` | `{ message }` |

알림 항목:

```json
{ "id": 12, "type": "SUBMISSION", "title": "답안 제출", "body": "이하랑 학생이 답안을 제출했습니다.",
  "evaluationId": "평가 UUID", "participantId": "학생 UUID", "createdAt": "2026-10-31T01:00:00Z", "read": false, "readAt": null }
```

| type | 언제 | 중복 |
|---|---|---|
| SUBMISSION | 학생이 직접 제출 | 학생당 1회 |
| EVALUATION_ENDED | 교사 종료 또는 시간 만료(자동 제출 인원 포함) | 평가당 1회 |
| WARNING | 창 이탈·캡처·복사 시도 등 첫 경고 | 학생당 1회(이후 경고는 채점 화면에서 확인) |

- 목록은 최신순입니다. size 1~100. 다른 교사의 알림 id는 404입니다.
- `PUT /api/users/me/settings` 본문 `{ "notify": false, "sound": true }` — 보낸 값만 바뀝니다. 기본값은 둘 다 true.
- `notify: false`면 그동안 서버가 새 알림을 **만들지 않습니다**. `sound`는 서버가 저장만 하고 재생 여부는 프런트가 정합니다.
- `PATCH /api/users/me` 본문 `{ "name": "박애란" }` (1~50자).
- `POST /api/users/me/password` 본문 `{ "currentPassword", "newPassword" }`(새 비밀번호 10~128자). 현재 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD, 같으면 400 PASSWORD_UNCHANGED. 성공하면 **지금 기기를 뺀 다른 로그인은 모두 해제**됩니다.
- 이용약관·개인정보 처리방침 문구는 백엔드에서 정하지 않습니다. 학교·운영 담당과 확정이 필요합니다.

## 5. 경고 이벤트 저장

`POST /api/evaluations/{id}/learning-events` (별칭: 기존 `/events`, `/api/student/evaluations/{id}/learning-events`). 학생 `X-Participant-Token`, 성공 201.

```json
{ "type": "WINDOW_BLUR", "occurredAt": "2026-10-31T00:00:00Z", "questionId": "문항 UUID" }
```

| type | 의미 | 경고 개수에 포함 |
|---|---|---|
| WINDOW_BLUR (= 기존 WINDOW_LEAVE) | 창 이탈 | O |
| CAPTURE_SHORTCUT (= 기존 SHORTCUT_CAPTURE) | 캡처 단축키 | O |
| COPY_ATTEMPT | 복사 시도 | O |
| FULLSCREEN_EXIT / FULLSCREEN_LEAVE | 전체화면 이탈 | O |
| DEVTOOLS_OPEN | 개발자 도구 열림 | O |
| PASTE | 붙여넣기 (`length` 글자 수 선택) | X (채점 신호) |
| AI_ANSWER_COPY | AI 답변 복사 (`conversationId` 선택) | X (채점 신호) |

- 기존 두 이름도 계속 받습니다. 새 화면은 위 이름을 쓰면 됩니다.
- questionId·length(0~10,000,000)·conversationId는 선택입니다. 본인 대화가 아닌 conversationId는 404 CONVERSATION_NOT_FOUND.
- 시각 범위·분당 60건 제한·시작 후 제출 전만 가능한 조건은 기존과 같습니다(FULLSCREEN_LEAVE 별칭 포함10개 유형 합산).
- 교사 `GET .../participants/{pid}/learning-events`에 그대로 나오고(details에 `warning: true/false`), 학생별 경고 개수는 `submissions`의 `warningCount`에 들어갑니다.
- 경고 기록만으로 부정행위를 판정하거나 감점하지 않습니다.

### 평가별 세부 안내 문구

이미 있습니다. 학생 `GET /api/evaluations/{id}/questions` 응답의 **`guidelines`** 필드(교사가 생성·설정 시 입력한 안내 문구)를 쓰세요.
이번에 같은 응답에 `aiLevel`(1~5)과 `aiLevelDescription`(단계 설명 문장)도 추가했습니다. 학생 화면의 "평가별 세부 규칙은 서버 API에서 제공하지 않는다" 문구는 지워도 됩니다.

## 6. 확인 요청 답변 요약

| 질문 | 답 |
|---|---|
| 1. AI 활용 분석은 누가 계산? | **서버**가 계산해 저장합니다. 프런트는 결과만 표시하세요. |
| 2. 루브릭 기준은 고정? | 기본 제안안 5개, **평가마다 변경 가능**(`PUT /rubric`, 합계 100, 채점 시작 전). 생성 화면에 입력 칸은 필수가 아니며, 필요하면 평가 상세 화면에 "기준 편집"으로 두는 것을 권합니다. |
| 3. 시간이 끝난 평가 상태? | **ENDED**. EXPIRED는 시작 안 한 대기 평가의 screen 값입니다. |
| 4. 종료 후 저장 유예·자동 제출? | 서버 자동 제출이 기본입니다. 종료 후 저장 유예는 기본0(비활성)이며, 명시적으로 설정할 때만 제한적으로 허용합니다. |
| 5. 알림 방식? | **폴링**. |
| 6. 경로·필드 이름 | 제안 경로를 그대로 썼습니다. 다른 점: 대화 id는 `conversationId`, 루브릭 저장은 기준별 PUT, 허용 범위 판단용 `compliance-review` 추가. |

## 7. 7-7 확인 요청 답변

| 질문 | 답 |
|---|---|
| 1. 분류·계산은 서버? | 서버입니다. |
| 2. 문단 출처 판정 가능? | 실제 출처는 판정할 수 없습니다. 문장 유사도(3글자 조각 포함률)와 복사 신고 기록을 참고 지표로 제공합니다. **정확도가 검증된 방법이 아니므로 "추정"으로 표시해 주세요**(`estimated: true`). 외부 AI 사용·저장되지 않은 입력은 알 수 없습니다. |
| 3. 언제 분석? | **제출 직후 자동**(수동·자동 제출 모두). 교사가 누를 필요 없으며, 기준값을 바꾼 뒤에는 `POST /analysis/run`으로 다시 계산합니다. |
| 4. AI 허용 단계 3개 vs 5개? | **5개로 늘렸습니다.** 기존 값과 호환됩니다. 아래 표. |
| 5. 배점을 평가마다? | 평가마다 바꿀 수 있습니다(위 2번). 채점 알고리즘의 가중치·경계값은 전체 설정(`lit.grading.*`)입니다. |

| 프로젝트 허용 단계 | aiPolicy | aiLevel |
|---|---|---|
| Level 1 | DISABLED | 1 |
| Level 2 | HINT_ONLY | 2 |
| Level 3 | **EDIT_ASSIST** (신규) | 3 |
| Level 4 | **TASK_ASSIST** (신규) | 4 |
| Level 5 | FULL | 5 |

- 설정 시 `aiPolicy`나 `aiLevel` 중 하나만 보내도 됩니다. 둘 다 보내면 같은 단계여야 합니다(아니면 400 INVALID_AI_POLICY).
- 교사 `GET .../questions`, 학생 `GET .../questions` 응답 모두 `aiPolicy`, `aiLevel`, `aiLevelDescription`을 내려 줍니다. 학생 안내와 채점이 같은 값을 씁니다.
- Level 1이면 서버가 AI 질문을 막습니다(403 AI_DISABLED, 기존과 같음). Level 3·4는 AI에 "학생 원고 편집만" / "과제 수행 후 학생이 검토할 근거 제시" 지시가 들어갑니다.


## 현재 요청 1~8번에 대한 정확한 상태

1. guidelines: jsoup 정리 HTML 지원을 추가했습니다. 굵게·밑줄·목록·제목·표 보존, 위험한 태그·속성 제거. 원본과 정리 결과 각각10,000자 상한입니다. 기존 evaluationContent와 같은 DB 값입니다.
2. 채점 기준: 15/25/20/25/15를 기본 제안안으로 제공하되 확정 표준으로 간주하지 않습니다. 평가별 PUT /rubric로 합계100점의 기준을 지정할 수 있습니다. 기존 5×20 레거시 리뷰는 유지됩니다.
3. 추천 점수: 제출 후 규칙 기반 계산 API를 검증했습니다. 외부 LLM 채점은 아닙니다. 근거가 없으면 추천null, 기여도는 교사 직접 채점, 최종 성적은 교사 확인 후 확정합니다.
4. studentContribution: 기존 카드 필드는 null 유지입니다. 화면에서 빼거나 측정 불가로 표시하세요. 별도 유사도 참고 값은 저자 기여도가 아닙니다.
5. 페이지네이션: 기존 students는 페이지당 최대100, 신규 submissions는 최대200입니다. 더 많은 학생은 page를 올려 조회하며 프런트 페이지 이동/무한 스크롤이 필요합니다.
6. 알림: 폴링 API를 검증했으며 SSE는 추가하지 않았습니다. GET unread-count/notifications를 사용합니다.
7. 배포: PR #13·#15·#17은 이미 병합·배포된 기존 기준입니다(#16은 이슈 번호). 이번 통합 변경은 #18~#24 이슈에 따라 기능별 PR로 분리합니다. 병합·운영 배포는 아직 하지 않았습니다.
8. Nginx: 기존 운영 서버의 client_max_body_size 8m은 이전 배포에서 이미 적용했습니다. 이번 작업에서는 서버 설정을 변경하지 않았습니다.

프런트 소스는 압축에 없으므로 이벤트 신고·카운트다운·UI 렌더링·기준 선택·프런트 타입 변경은 수정하지 않았습니다. 백엔드 새 API의 요청/응답은 AI_GRADING.md 및 통합 검토서를 참고하세요.
