# 수행평가 틀·반별 회차 API

2026-11-01 로컬 커밋에 포함한 구현입니다. 푸시·운영 배포하지 않았습니다. 기존 독립 evaluation은 자동 변환하지 않으며 기존 API와 데이터를 유지합니다.

## 모델과 진행 흐름

- `assessmentId`: 수행평가 **틀** ID. 제목·HTML guidelines·AI 정책·평가 항목·부정행위 설정·문항을 저장합니다.
- `classId` = `evaluationId`: 반 **회차** ID. 기존 학생/참가자/답안/대화/리포트/채점 API에는 이 값을 넣습니다. 틀 ID를 넣지 않습니다.
- 반 생성: 프런트용 `state=READY`, 기존 evaluation 상태는 `evaluationState=WAITING`이며 PIN·QR·expiresAt·endsAt은 null입니다. 아직 참여할 수 없습니다.
- 대기실 열기: `POST .../classes/{classId}/lobby`로 PIN·QR을 발급합니다. `state=WAITING`, `evaluationState=WAITING`, `started_at/endsAt=null`이며 학생은 PIN으로 참가해 기다립니다. 요청의 `expiresInMinutes`는 대기실 PIN 유효 시간입니다.
- 실제 시작: 대기실을 연 후 `POST .../classes/{classId}/start`를 호출하면 기존 PIN을 유지하고 `STARTED`로 바뀝니다. 이때부터 풀이 시간이 흐릅니다. 기존 계약대로 시작 요청의 `expiresInMinutes`가 실제 풀이 시간입니다. 참가자0명 시작과 시작 후 추가 참가는 기존처럼 허용합니다. 대기실 없이 start를 호출하면409 `CLASS_LOBBY_REQUIRED`입니다.
- 기존 독립 평가의 대기실→참가자 확인→시작 방식은 유지합니다. **반 회차만** 시작 후 새 참가를 허용합니다.
- 반 종료: 기존 자동 제출·분석 예약·교사 알림을 재사용합니다. 시작 전 반 종료는409, 이미 종료된 반 종료는 같은 결과를 반환합니다.

## 엔드포인트

모든 아래 요청에는 교사 `Authorization: Bearer <accessToken>`이 필요합니다. 다른 교사의 틀/반은404입니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/teacher/assessments` | 틀 생성,201 |
| GET | `/api/teacher/assessments?page=0&size=20` | 교사 소유 틀 목록, size1~100 |
| GET | `/api/teacher/assessments/{assessmentId}` | 공통 설정·반 목록 조회 |
| PUT | `/api/teacher/assessments/{assessmentId}` | 틀 전체 수정, 대기 반에만 반영 |
| DELETE | `/api/teacher/assessments/{assessmentId}` | 틀·하위 반·기록 전체 삭제,204 |
| POST | `/api/teacher/assessments/{assessmentId}/classes` | `{"className":"1반"}`,201 |
| GET | `/api/teacher/assessments/{assessmentId}/classes` | 반 목록 배열 |
| DELETE | `/api/teacher/assessments/{assessmentId}/classes/{classId}` | 시작 전 반만 삭제,204 |
| POST | `/api/teacher/assessments/{assessmentId}/classes/{classId}/lobby` | 대기실 열기·PIN 발급 `{"expiresInMinutes":60}` |
| POST | `/api/teacher/assessments/{assessmentId}/classes/{classId}/start` | 실제 평가 시작 `{"expiresInMinutes":45}` |
| POST | `/api/teacher/assessments/{assessmentId}/classes/{classId}/end` | 반 종료 |

생성·PUT 예시:

```json
{
  "title": "자료를 바탕으로 주장하기",
  "guidelines": "<h2>수행평가 안내</h2><p><strong>근거</strong>를 제시하세요.</p>",
  "aiPolicy": "HINT_ONLY",
  "criteria": ["근거의 적절성", "표현의 명확성"],
  "antiCheat": ["TAB_LEAVE", "PRINT_BLOCK", "FULLSCREEN_LEAVE", "DEVTOOLS"],
  "questions": [
    {"position":1,"title":"문항1","content":"자료를 읽고 주장을 작성하세요.","minChars":100,"maxChars":2000}
  ],
  "durationMinutes":45
}
```

- 제목1~100자, 문항1~100개, position은1이상·중복 불가. 문항 제목200자·내용10,000자 이하입니다.
- `guidelines`: 생략 시 빈 문자열, 안전 HTML 정리 후 저장. 원본/정리 결과 각10,000자 이하입니다.
- `aiPolicy`: `DISABLED`, `HINT_ONLY`, `EDIT_ASSIST`, `TASK_ASSIST`, `FULL` 중 필수입니다.
- `durationMinutes`: 생략 시60,1~1440. 대기 반에 복사되는 기본값이며, 시작 요청의 `expiresInMinutes`가 실제 회차 시간을 결정합니다.
- `criteria`: 선택한 평가 항목 제목 배열입니다. 최대10개, 각1~100자이며 중복·빈 문자열은 거절합니다. 생략 시 `[]`입니다. 제목 선택만으로 임의 배점을 만들지 않습니다. 별도의 반별 `/rubric`·점수 확정 API는 기존 계약을 유지하며, 루브릭 수정/초기화가 이 제목 배열을 덮어쓰지 않습니다.
- 기존 상세 루브릭 객체 배열(`name,maxScore,description,indicator,levelDescriptions`)도 호환합니다. 상세 형식은 합계100점이며 indicator 생략 시 MANUAL입니다. 제목과 객체를 한 배열에 섞을 수 없습니다. 제목 배열과 상세 루브릭 배열은 다른 계약이므로 새 프런트는 제목 배열을 사용하세요.
- `antiCheat`: 허용 값은 `TAB_LEAVE`, `PRINT_BLOCK`, `FULLSCREEN_LEAVE`, `DEVTOOLS` 코드 배열입니다. 알 수 없는 코드·중복 코드는400입니다. 생략 시 `[]`입니다. 이전 구현의 JSON 객체도16KiB 이하일 때 보존·반환합니다. **이번 기능은 설정 저장·스냅샷 전달이며, 브라우저의 인쇄나 창 이동 자체를 서버에서 막을 수는 없습니다.** 프런트가 이 코드에 맞춰 감지·인쇄 차단을 켜고 꺼야 합니다. 기존 이벤트 API는 신고를 기록하고, 신고만으로 부정행위를 확정하지 않습니다.
- PUT은 전체 교체입니다. 생략한 안내/기준/부정행위 설정은 기본값으로 초기화됩니다. 변경할 값뿐 아니라 공통 설정 전체를 보내세요.

## 응답 계약

틀 상세/생성/수정 응답은 `id,title,guidelines,aiPolicy,criteria,antiCheat,questions,durationMinutes,revision,questionCount,classCount,classes,createdAt,updatedAt`을 포함합니다.

목록 응답은 `items,page,size,totalElements,totalPages`이며 각 item에 `id,title,questionCount,classCount,classes,revision,updatedAt`이 있습니다. `classes`는 아래 형태입니다.

```json
{
  "classId":"회차 UUID",
  "evaluationId":"classId와 같은 UUID",
  "assessmentId":"틀 UUID",
  "className":"1반",
  "state":"STARTED",
  "evaluationState":"STARTED",
  "pin":"001234",
  "joinUrl":"https://프런트/join?pin=001234",
  "qrUrl":"/api/teacher/evaluations/회차UUID/qr",
  "expiresAt":"2026-11-01T03:45:00Z",
  "endsAt":"2026-11-01T03:45:00Z",
  "participantCount":25,
  "submittedCount":10,
  "templateRevision":1
}
```

`participantCount`는 나간 학생을 포함한 등록 참가자 수입니다. PIN은 앞자리0을 보존하는 문자열입니다. QR 조회는 기존 교사 토큰이 필요합니다. 대기실을 열기 전 READY의 PIN/링크/시간은 null이고, 연 뒤 WAITING에는 PIN·참여 링크·PIN 만료 시각이 있으며 endsAt은 null입니다. 새 반 API의 state는 READY/WAITING/STARTED/ENDED, evaluationState는 WAITING/STARTED/ENDED입니다. 기존 state API는 WAITING 이름을 유지합니다.

기존 `GET /api/teacher/evaluations`와 상세 응답에도 `assessmentId,className`을 추가합니다. 독립 평가는 두 값이 null입니다. 학생 state의 `endsAt/serverNow` 계약은 그대로 유지합니다.

## 수정·삭제 정책

- 틀을 수정하면 revision을 올리고 `WAITING` 반의 모든 공통 설정을 같은 트랜잭션에서 교체합니다. 대기 반 문항 ID는 바뀔 수 있습니다.
- `STARTED`/`ENDED` 반은 제목·안내·문항 ID·AI 정책·글자 수·기준·부정행위 설정을 보존합니다. 새 반은 최신 revision을 복사합니다.
- 부모 잠금 후 반 잠금 순서로 생성·수정·시작·삭제를 직렬화합니다. 동시에 수정/시작해도 설정의 일부만 복사되지 않습니다.
- 반 이름은 공백 제거·NFKC 정규화 후1~30 Unicode 코드 포인트입니다. 같은 틀에서 대소문자/전각·반각을 구분하지 않고 중복을 차단합니다. 다른 틀에서는 같은 이름을 사용할 수 있습니다.
- 기존 evaluation configuration/content/PATCH/rubric 수정·초기화·start/delete 경로로 회차의 공통 설정을 우회 변경하면409 `CLASS_CONFIGURATION_MANAGED`입니다. 반별 학생 채점·점수 확정은 기존 API를 사용합니다.
- 반만 삭제할 때는 `WAITING`이어야 합니다. 종료된 반도 개별 삭제는409 `CLASS_DELETE_REQUIRES_WAITING`입니다.
- 틀 삭제는 `STARTED` 반이 하나라도 있으면409 `ASSESSMENT_IN_PROGRESS`입니다. 진행 중인 반이 없으면 종료된 반까지 포함하여 **답안·대화·참가자·제출·채점·리포트·알림을 복구 불가능하게 함께 삭제**합니다. 프런트 확인 창이 필요합니다.

## 문항 글자 수와 기존 configuration 확장

- `minChars`, `maxChars`: 각각 선택값이며 명시하면1~99,999입니다. 생략/null이면 그쪽 제한이 없습니다. DB 내부 최소값0은 제한 없음으로 보관하고 조회에서는 null을 반환합니다. 최소가 최대를 넘으면400 `INVALID_CHARACTER_LIMIT`입니다.
- HTML 태그가 아닌 jsoup의 표시 텍스트(`text()`)를 Unicode 코드 포인트로 계산합니다. HTML 엔티티는 복원되고 연속 공백은 정규화됩니다. 한글1자·이모지1코드포인트를 각각1로 계산합니다.
- 저장에서는 최소 미달 초안을 허용합니다. 최대 초과는400 `ANSWER_TOO_LONG`이며 기존 저장본·version을 유지합니다.
- `submission-preview`에 `answerValidationErrors: [{questionId,actualChars,minChars,maxChars}]`가 추가됩니다. 오류가 있으면 `canConfirm=false`이고 수동 제출은400 `ANSWER_LENGTH_INVALID`입니다.
- 시간 만료·교사 종료 자동 제출은 짧은 초안도 그대로 보존합니다. 입력 제한 때문에 자동 제출을 실패시키지 않습니다.
- HTML UTF-8 1MiB 제한은 기존대로 별도로 적용됩니다. 기존 문항의 제한 없음 상태와 저장 데이터는 유지합니다.
- 기존 `PUT /api/teacher/evaluations/{id}/configuration`도 문항의 min/max와 `criteria,antiCheat`를 받습니다. 기존 API에서는 criteria/antiCheat를 생략하면 기존 값을 유지합니다.
- 교사 configuration과 학생 workspace 응답에 `criteria,antiCheat,assessmentId,className`, 각 question에 `minChars,maxChars`가 추가됩니다. 제목 배열은 선택 항목 메타데이터이며, 상세 루브릭 객체를 지정한 경우에만 반별 기존 rubric API의 배점과 연동됩니다.

## Postman 순서

컬렉션의 `수행평가 틀·반별 회차` 폴더에서 틀 생성→반 생성→대기실 열기→학생 참여→반 시작을 실행합니다. 응답 스크립트가 `assessmentId,classId,evaluationId,pin`을 저장합니다. 이후 기존 PIN 참가·workspace·답안·제출·채점 요청을 사용할 수 있습니다. 틀 삭제 요청은 전체 기록을 지우므로 테스트용 틀에서만 실행하세요.

## 진행 중 시간 연장

`POST /api/teacher/evaluations/{evaluationId}/extend`

```json
{"additionalMinutes":10}
```

- 소유 교사만 호출할 수 있고, 추가 시간은1~600분입니다.
- 독립 평가와 반 회차 모두 지원합니다. 반 회차에는 classId를 evaluationId로 넣습니다.
- 현재 마감 전 STARTED 상태에만 허용합니다. 시작 전/종료/실제 endsAt이 이미 지난 평가는409 EVALUATION_NOT_RUNNING이며 되살리지 않습니다.
- 기존 endsAt에 시간을 더하고 expiresAt도 함께 갱신합니다. PIN·질문·답안은 유지합니다.
- 응답은 기존 로비(`evaluationId,title,state,screen,participantCount,participants,endsAt,serverNow`)입니다. 학생 state의 endsAt과 반 목록의 endsAt도 즉시 같은 값으로 바뀝니다.
- 중복 요청은 각각 추가 연장입니다. 프런트는 연장 버튼의 전송 중 중복 클릭을 막아야 합니다.

## 채점 화면 폴링 알림 보강

SSE/WebSocket은 추가하지 않고 요청서의 대안으로 기존 조회 응답을 확장했습니다.

- `GET /api/teacher/evaluations/{id}/students`의 각 items/students에 `warningCount,lastEventAt`을 추가합니다.
- `GET /api/teacher/evaluations/{id}/submissions`의 각 item에도 lastEventAt을 추가하고 기존 warningCount를 유지합니다.
- warningCount는 기존 창 이탈·캡처·복사 시도 경고 이벤트의 누적 수입니다. lastEventAt은 서버 수신/기록 시각의 최댓값이고 기록이 없으면 null입니다.
- 프런트는 같은 참가자의 이전 submissionStatus·warningCount·lastEventAt과 비교하여 제출/경고 토스트를 표시할 수 있습니다.
- 학생의 WINDOW_LEAVE·SHORTCUT_CAPTURE 신고는 기존 POST .../events를 재사용합니다. 경고 집계와 교사 알림 생성까지 통합 테스트했습니다. 프런트 이벤트 연결은 이 저장소 범위에 포함되지 않습니다.

## 검증 결과

- `./mvnw -q clean verify`: 테스트126개, 실패0·오류0·건너뜀0, 실행 JAR 빌드 성공.
- 코드 테스트는 메모리 DB와 가짜 AI 제공자를 사용합니다. 운영 DB를 변경하지 않았습니다.
- 2026-11-01 별도 운영 연결 확인에서 현재 모델 gemini-3.1-flash-lite의 최소 단독 요청도 HTTP503 UNAVAILABLE을 반환했습니다. 키가 설정되어 있고 앱은 healthy였으며, 커스텀 AI endpoint는 없었습니다. 새 API 코드를 운영에 적용한 결과가 아닙니다.
- 로컬 API 구현 중에는 운영 설정을 유지했습니다. 이후 AI 장애 대응으로 같은 키에서200·정상 텍스트를 확인한 gemini-3.5-flash-lite로 운영 모델만 변경하고 기존 이미지로 재시작했습니다. 공개 HTTPS readiness는 UP입니다.
- 새 틀·반·연장 API는 로컬 커밋에 포함했으며 푸시·운영 배포하지 않았습니다.

## 교사 버튼과 학생 화면 연결 (대기실 수정)

1. 교사 ‘평가 시작하러 가기’: `POST /api/teacher/assessments/{assessmentId}/classes/{classId}/lobby`, 본문 `{"expiresInMinutes":60}`. 응답의 PIN·QR을 표시하고 대기실로 이동합니다. 이 버튼에서 `/start`를 호출하지 마세요.
2. 학생 PIN 확인·참가: 기존 `/api/participation/verify`, `/api/participation/join`을 사용합니다. join 응답의 `lobby.screen=WAITING`을 표시하고 참가자 토큰을 유지합니다.
3. 교사 참가자 목록: `GET /api/teacher/evaluations/{evaluationId}/participants`. 틀 ID가 아닌 반 응답의 evaluationId를 사용합니다.
4. 대기실 ‘평가 시작하기’: `POST .../classes/{classId}/start`, 본문 `{"expiresInMinutes":45}`. 성공 후 교사 진행 화면으로 이동합니다.
5. 학생은 기존 `GET /api/student/evaluations/{evaluationId}/state`를 참가자 토큰으로 주기 조회합니다. `screen=SOLVING` 또는 `state=STARTED`가 되면 workspace를 조회하고 풀이 화면으로 이동합니다. 시작 전 workspace·답안 저장은409 `EVALUATION_NOT_STARTED`입니다.

대기 중에는 started_at·endsAt이 없고 풀이 시간을 차감하지 않습니다. 실제 시작 시 서버 시각으로 `endsAt=started_at+시작 요청의 expiresInMinutes`를 계산합니다.

활성 대기실 `/lobby` 재호출은 PIN·참가자·유효 시간을 유지합니다. 만료된 대기실은 같은 PIN·참가자를 유지해 새 대기실 유효 시간을 부여합니다. 만료된 상태의 `/start`는410 `PIN_EXPIRED`이며 먼저 대기실을 다시 열어야 합니다. 시작·종료 후 `/lobby`는409 `CLASS_LOBBY_LOCKED`, 중복 start는409입니다. 별도 DB 마이그레이션 없이 기존 null PIN·WAITING 모델을 사용하며 이미 시작된 반은 변경하지 않습니다.

프런트 연결은 위 두 버튼의 API를 구분하고 WAITING 상태를 처리하도록 함께 변경해야 합니다. 서버가 자동으로 화면을 이동시키거나 실시간 push하지는 않습니다. 기존 시작 전 반 삭제 정책은 열린 WAITING에도 적용되며, 삭제하면 참가자 기록도 삭제됩니다.
