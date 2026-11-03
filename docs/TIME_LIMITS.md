# 제한 시간·서버 자동 제출 API

이 변경은 PR #13의 프런트 연동 변경 위에 쌓은 후속 변경입니다. 병합·배포 전까지 운영 API에는 적용되지 않습니다.

## 제한 시간 설정

`POST /api/teacher/evaluations` 요청에 선택 필드 `durationMinutes`를 추가합니다 (정수 1~1440). 생략하면 기존 필수 `expiresInMinutes` 값을 사용합니다.

```json
{"title":"수학 수행평가","expiresInMinutes":60,"durationMinutes":30,"guidelines":"풀이 근거를 작성하세요."}
```

expiresInMinutes는 생성 후 시작 전까지의 PIN 유효 기간입니다. durationMinutes는 실제 풀이 제한 시간입니다. 교사 시작 요청이 성공한 서버 시각을 started_at으로 저장하고 `ends_at = started_at + durationMinutes`로 계산합니다. 대기한 시간이 풀이 시간에서 차감되지 않습니다. 시작 후 기존 expiresAt도 같은 종료 시각으로 맞춰 기존 클라이언트와 호환합니다.

`PUT /api/teacher/evaluations/{id}/configuration`에서 aiPolicy·questions와 함께 durationMinutes를 보내 대기 중에 변경할 수 있습니다. 생략/null이면 기존 값 유지. `GET .../configuration` 응답에 durationMinutes가 포함됩니다. 시작 후 변경은 409 CONFIGURATION_LOCKED, 만료된 대기 평가에서 시간 변경은 410 PIN_EXPIRED입니다.

## 학생 상태와 서버 시계

`GET /api/student/evaluations/{id}/state` (기존 `/api/evaluations/{id}/state`도 동일). 해당 평가의 `X-Participant-Token` 헤더가 필수입니다.

기존 Lobby 응답을 유지하면서 endsAt·serverNow를 추가합니다.

```json
{
  "evaluationId":"11111111-1111-4111-8111-111111111111",
  "title":"수학 수행평가",
  "state":"STARTED",
  "screen":"SOLVING",
  "participantCount":1,
  "participants":[{"id":"22222222-2222-4222-8222-222222222222","nickname":"학생","presence":"CONNECTED"}],
  "endsAt":"2026-10-31T01:30:00Z",
  "serverNow":"2026-10-31T01:00:00Z"
}
```

- 날짜는 UTC 오프셋인 `Z`가 포함된 ISO 8601입니다. 프런트에서 원하는 지역 시간으로 표시합니다.
- 시작 전 endsAt=null, serverNow는 항상 현재 서버 시각입니다.
- 남은 시간 기준은 `Date.parse(endsAt) - Date.parse(serverNow)`입니다. 응답 수신 후 performance.now()의 경과 시간을 차감하면 학생 컴퓨터의 날짜·시계 설정에 의존하지 않습니다. 네트워크 왕복 지연은 필요에 따라 보정하고 폴링 응답으로 재동기화하세요.
- 교사가 수동 종료하면 state=ENDED가 됩니다. endsAt은 원래 예정 종료 시각을 유지합니다. 남은 시간이 있더라도 state=ENDED면 종료 처리하세요.
- 자동/수동 제출한 학생은 screen=SUBMITTED가 우선입니다. 종료 시 state=ENDED와 screen=SUBMITTED가 함께 올 수 있습니다.

## 서버 자동 제출

**10초 유예 대신 서버 직접 자동 제출 방식을 적용합니다.** 제한 시각 이후의 새 저장·수동 제출·AI 질문은 허용하지 않습니다.

- 실행 중인 서버가 기본 1초 간격으로 만료된 STARTED 평가를 찾아 종료하고 최신 저장 답안을 제출합니다. 상태 조회·풀이 조회·최종 제출 조회도 기한이 지난 경우 종료 처리를 수행합니다.
- 학생이 브라우저를 닫거나 네트워크가 끊겼어도 제출됩니다. 명시적으로 나간 참가자(active=false)도 포함합니다.
- 브라우저에만 있고 저장하지 못한 입력은 서버가 복구할 수 없습니다. 프런트는 마감 전 저장 요청을 완료하고, 마감 후에는 GET submission으로 확정 내용을 확인하세요.
- 수동 종료도 같은 방식으로 미제출자를 제출합니다. 기존 수동 제출 시각·내용·해시는 보존합니다.
- 문항이 없거나 미작성이어도 빈 답안을 포함한 제출을 기록합니다. 시작 전 대기 평가의 만료·취소는 자동 제출하지 않습니다.
- PENDING AI 요청은 FAILED/EVALUATION_CLOSED로 마감한 뒤 snapshot에 포함합니다. 늦은 AI 성공 결과가 확정 제출을 바꾸지 않습니다.
- 저장·수동 제출·자동 제출·종료는 같은 평가 행 잠금으로 직렬 처리합니다. 먼저 성공한 저장은 snapshot에 포함되고, 종료가 먼저 완료되면 새 저장은 거절됩니다. 제출과 종료 재시도로 중복 제출·이벤트를 생성하지 않습니다.
- snapshot에는 마감 시 답안·대화·학습 기록이 고정됩니다. 이후 기존 리포트 생성 작업에서 처리할 수 있습니다.
- 백그라운드 처리는 정확한 실시간 실행을 보장하지 않습니다. 정상 부하에서 기본 검사 간격은 1초이며 처리 대기·서버 중단 시 늦어질 수 있습니다. 마감 시각은 DB에 저장되므로 재기동 후 지난 기한을 처리합니다. 프런트 카운트다운은 서버 작업의 실행 시각 대신 endsAt을 사용합니다.

## 제출 결과

기존 `GET /api/evaluations/{id}/submission` 또는 학생 별칭 `/api/student/evaluations/{id}/submission` 응답에 submissionMode를 추가합니다.

```json
{
  "status":"SUBMITTED",
  "submittedAt":"2026-10-31T01:30:00Z",
  "snapshot":{},
  "submissionMode":"AUTO_DEADLINE"
}
```

snapshot은 기존 ReviewContent 전체입니다 (위 빈 객체는 필드 위치만 설명한 축약 예시).

- MANUAL: 학생의 확인 후 수동 제출 (기존 제출의 기본값).
- AUTO_DEADLINE: 제한 시간 만료에 따른 자동 제출.
- AUTO_TEACHER_END: 마감 전 교사 수동 종료에 따른 자동 제출.

학습 타임라인의 SUBMITTED 이벤트에도 자동 제출의 submissionMode가 포함됩니다. 제출 완료 후 도착한 오래된 수동 제출 요청은 기존 내용과 확인 토큰이 다르면 409 ALREADY_SUBMITTED입니다. 이때 GET submission으로 확정 결과를 확인합니다. 서버 작업 전 마감 직후 새 저장은 410 PIN_EXPIRED, 자동 제출 완료 후 새 저장은 409 ALREADY_SUBMITTED입니다.

## 기존 DB

기존 평가·답안·제출을 삭제하지 않습니다. 기존 제출은 MANUAL로 유지합니다. 이미 종료된 과거 평가는 소급 자동 제출하지 않습니다. 기존 STARTED 평가는 이전 expiresAt을 endsAt으로 보존합니다. 과거 데이터에는 원래 제한 시간이 저장돼 있지 않아 기존 WAITING 평가의 durationMinutes는 60으로 초기화합니다. 필요하면 시작 전에 configuration에서 변경하세요. 새 평가는 요청한 값을 저장합니다.

## Postman

추가된 ‘제한 시간·자동 제출’ 폴더를 사용합니다. 기존 teacherAccessToken·participantToken·evaluationId 변수를 설정한 후 학생 상태 → 교사 종료 → 학생 제출 조회 순서로 확인할 수 있습니다. 교사 종료 요청은 실제 평가를 종료하므로 테스트 평가에서만 실행하세요.
