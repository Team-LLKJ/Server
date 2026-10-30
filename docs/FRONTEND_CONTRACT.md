# 프런트 변경 연동 (2026-10-31)

## 수행평가 안내

`guidelines`는 기존 `evaluationContent`와 동일한 `evaluations.evaluation_content` DB 값을 사용합니다. 기존 저장값은 그대로 새 필드로 조회됩니다. 일반 텍스트 최대 10,000자이며 공백·줄바꿈을 유지합니다. 학생 화면에서는 텍스트로 출력하세요.

- `POST /api/teacher/evaluations`: `guidelines` 선택 필드 추가. 생략/null이면 기존 evaluationContent를 사용하거나 빈 문자열로 생성합니다. 두 필드를 동시에 보내면 값이 같아야 합니다 (다르면 400 INVALID_REQUEST).
- `PUT /api/teacher/evaluations/{id}/configuration`: 기존 `/questions`의 별칭. aiPolicy·questions 전체와 함께 guidelines를 보냅니다. guidelines 생략/null이면 기존 내용 유지, `""`이면 삭제합니다. 새 문항 ID가 발급되므로 응답을 사용하세요. WAITING에서만 문항을 변경하고, 안내 변경은 만료 전만 가능합니다.
- `GET /api/teacher/evaluations/{id}/configuration`: 기존 `/questions`의 별칭. 응답 `{ evaluationId, aiPolicy, questions, guidelines }`.
- `GET /api/student/evaluations/{id}/workspace`: 기존 `GET /api/evaluations/{id}/questions`의 별칭. 응답에 `guidelines`와 기존 `evaluationContent`를 같은 값으로 제공합니다. 기존대로 시작 후 조회 가능합니다. 대기 중 안내 조회는 기존 `/api/evaluations/{id}/content`를 사용합니다.

교사 경로는 소유 교사의 Authorization Bearer 토큰, 학생 경로는 해당 평가의 `X-Participant-Token`이 필요합니다. 새 학생 경로도 동일한 권한 검사와 참가 상태 검사를 수행합니다.

```json
{
  "aiPolicy": "HINT_ONLY",
  "guidelines": "풀이 근거를 작성하세요.\nAI는 힌트 확인에만 사용하세요.",
  "questions": [{"position":1,"title":"문항 1","content":"분수 덧셈을 설명하세요."}]
}
```

기존 `/content` API도 계속 지원하며 새 guidelines와 동기화됩니다. 기존 `/questions` 설정 요청에서 guidelines를 보내지 않아도 안내가 지워지지 않습니다.

## 창 이탈·캡처 단축키 이벤트

`POST /api/student/evaluations/{id}/events` (별칭 `/api/evaluations/{id}/events`), 성공 201.

```json
{"type":"WINDOW_LEAVE","occurredAt":"2026-10-31T00:00:00Z","questionId":"해당 평가 문항 UUID"}
```

- type은 WINDOW_LEAVE 또는 SHORTCUT_CAPTURE만 허용합니다. AI_COMPLETED 등 서버 이벤트를 위조할 수 없습니다.
- occurredAt은 필수 ISO-8601 시각입니다. 서버 현재 시각보다 5분 넘게 미래이거나 24시간보다 과거이면 400 INVALID_EVENT_TIME.
- questionId는 선택입니다. 해당 평가에 속하는 문항만 허용합니다. 다른 평가 문항은 404 QUESTION_NOT_FOUND.
- 시작 후·제출 전·종료/만료 전의 active 참가자만 전송할 수 있습니다. 분당 참가자당 60건을 초과하면 429 EVENT_RATE_LIMITED. 이 제한은 답안 저장에 적용되지 않습니다.
- 응답과 기존 교사 학습 이벤트 조회에는 `{ id, questionId, type, details: { occurredAt, clientReported: true }, createdAt }` 형태로 포함됩니다. createdAt은 서버 수신 시각, details.occurredAt은 클라이언트가 신고한 시각입니다. 타임라인 순서는 기존대로 서버 id 기준입니다.
- 이벤트는 제출 snapshot에도 포함됩니다. 화면 포커스 변경·단축키 감지는 부정행위의 확정 근거가 아니며 자동 감점하지 않습니다. 단축키를 감지했다고 실제 화면 캡처를 확인한 것은 아닙니다.
- 이 API에는 멱등 키가 없습니다. 응답을 받지 못한 POST를 자동 반복하면 중복 이벤트가 생길 수 있습니다. 같은 UI 사건을 중복 전송하지 않도록 처리하세요.

## 답안 자동 저장·HTML

기존 저장 경로 또는 `/api/student/evaluations/{id}/questions/{questionId}/answer`에 PUT합니다. 버전 충돌은 기존 409 ANSWER_VERSION_CONFLICT입니다. 답안 저장 자체의 분당 호출 제한은 없으며 3초 자동 저장을 지원합니다. 변경된 경우만 저장하고, 요청을 순차 처리해 응답 version을 다음 expectedVersion으로 사용하세요.

- content 입력 상한은 **UTF-8 1MiB (1,048,576 bytes)** 입니다. 초과는 413 ANSWER_TOO_LARGE. 글자 수가 아닌 바이트로 계산하세요 (`new TextEncoder().encode(html).length`). DB는 CLOB로 확장하며 기존 답안을 보존합니다.
- HTML 표의 table, thead, tbody, tr, th, td, colgroup, col과 colspan, rowspan, colwidth를 허용합니다. 표만 있고 셀의 텍스트가 없어도 답안으로 저장됩니다.
- jsoup 허용 목록으로 script·이벤트 속성·javascript: 링크를 제거합니다. 임의 style/class, iframe, SVG는 보존하지 않습니다. 응답 content는 정리된 HTML이므로 저장 응답을 기준으로 관리하세요. 기존 일반 텍스트도 계속 지원합니다.
- 이미지 업로드 API는 추가하지 않습니다. 이전 답안의 HTTP(S) 이미지와 PNG/JPEG/GIF/WebP data URI는 크기 제한 안에서 유지합니다. SVG/HTML data URI와 실행 가능한 속성은 제거합니다. 기존 DB를 일괄 정리하지 않으며 다음 저장 시 적용됩니다. 과거 HTML을 렌더링할 때도 프런트의 안전한 HTML 렌더링 처리를 유지하세요.
- source/aiRequestId는 기존 API 호환을 위해 유지합니다. 현재 프런트는 `source:"DIRECT"`, aiRequestId 생략으로 보내면 됩니다. DIRECT는 프런트 신고값이며 ‘AI 미사용’의 검증 결과가 아닙니다. AI 대화의 실제 기록은 계속 별도로 저장됩니다.
- 학습 이벤트·확정 제출에는 저장된 HTML이 포함됩니다. PDF에서는 HTML 표를 행·셀 텍스트와 구분자로 변환하며 브라우저 표 레이아웃을 그대로 재현하지는 않습니다.

새 /student 접두어는 문항 선택·답안 저장·AI 질문·대화/학습 이벤트 조회·제출 미리보기/제출/최종 제출 조회에도 기존 /api/evaluations 경로와 같은 동작의 별칭으로 지원합니다.

## 배포 시 요청 본문 크기

1MiB HTML은 JSON의 따옴표·제어문자 이스케이프로 요청 본문이 더 커질 수 있습니다. Nginx `client_max_body_size`를 **8m**로 설정하고 `nginx -t` 후 reload해야 합니다 (`infra/nginx.conf.example`). 이 설정은 기존 EC2에 자동 적용되지 않습니다. 백엔드는 JSON을 파싱한 content의 UTF-8 바이트 수를 기준으로 1MiB를 검사합니다.

HTML 정리는 [jsoup 공식 허용 목록 API](https://jsoup.org/apidocs/org/jsoup/safety/Safelist)를 사용합니다.

제한 시간 후속 변경에서는 시작한 평가의 만료·수동 종료 시 서버가 최신 저장 답안을 자동 제출합니다. 종료 시각·서버 시계·durationMinutes 설정과 상세 동작은 [제한 시간 API](TIME_LIMITS.md)를 참고하세요.
