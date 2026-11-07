# 프런트 요청 10항목: 최종 백엔드 계약

이 문서는 알림 삭제·비밀번호 찾기·반 대기실·서식 있는 HWP·경고 이벤트를 함께 통합한 `feature/frontend-contract-completion` 기준입니다. PR 머지와 운영 배포 성공 전에는 아래 변경이 운영에 반영됐다고 판단하지 마세요. 프런트 코드는 변경하지 않습니다.

## 1. 기능 배포와 비밀번호 재설정 URL

알림 DELETE 2개, 비밀번호 재설정 POST 2개, 반 대기실 POST를 포함합니다. 개별 상세 명세는 NOTIFICATION_DELETION.md, PASSWORD_RESET.md, ASSESSMENT_CLASSES.md입니다.

배포 시 명시적인 `LIT_PASSWORD_RESET_URL`이 없거나 비어 있으면 production의 `PRODUCTION_URL` origin에 `/reset-password`를 붙여 초기화합니다. 이미 설정한 URL 및 SMTP 비밀값은 보존합니다. SMTP 미설정·발송 실패는 기존대로503/502이며, 성공 응답은 수신함 도착 보장이 아닙니다.

## 2. 대기실 응답

기존 `state=READY`를 유지합니다. `lobbyState`를 추가해 `READY`(미개방), `WAITING`(PIN이 있는 대기실), `STARTED`, `ENDED`를 구분합니다. `evaluationState`는 기존 평가 상태 WAITING/STARTED/ENDED입니다. WAITING 대기실의 expiresAt이 지났다면 PIN 만료이므로 다시 /lobby를 호출하고, 상태값만으로 PIN 유효성을 판단하지 마세요.

`POST /api/teacher/assessments/{assessmentId}/classes/{classId}/lobby`, 요청 `{ "expiresInMinutes":60 }`:

```json
{
  "classId":"class-id","evaluationId":"class-id","assessmentId":"assessment-id","className":"1반",
  "state":"READY","lobbyState":"WAITING","evaluationState":"WAITING","pin":"012345",
  "joinUrl":"https://example.test/student/join?pin=012345",
  "qrUrl":"/api/teacher/evaluations/class-id/qr",
  "expiresAt":"2026-11-07T10:00:00Z","endsAt":null,
  "participantCount":0,"submittedCount":0,"templateRevision":1
}
```

위 ID·시각·호스트는 구조 설명용입니다. 실제 서버 응답을 사용하세요. /lobby는 PIN 발급만 하며, /start에서 풀이 타이머를 시작합니다. start 전에 lobby가 없으면409 CLASS_LOBBY_REQUIRED입니다.

## 3. 과정 평가 모델

### 루브릭

GET `/api/teacher/evaluations/{id}/process-grading/rubric`:

```json
{
  "rubricVersion":"LIT_PROCESS_RUBRIC_V1","provisional":true,
  "criteria":[
    {"criterion":"PURPOSE","name":"AI 활용 목적의 적절성","maxScore":15},
    {"criterion":"SELF_DIRECTION","name":"자기 주도적 사고","maxScore":25},
    {"criterion":"CRITICAL_REVIEW","name":"AI 답변의 비판적 검토","maxScore":20},
    {"criterion":"CONTRIBUTION","name":"답안에 대한 학생의 기여","maxScore":25},
    {"criterion":"TRANSPARENCY","name":"AI 활용 과정의 투명성","maxScore":15}
  ],
  "criteriaMarkdown":"전체 상세 루브릭 Markdown"
}
```

### Job.result.cells[] 한 칸

```json
{
  "questionId":"q1","criterion":"PURPOSE","criterionName":"AI 활용 목적의 적절성",
  "level":3,"score":11.25,"maxScore":15,"status":"RECOMMENDED",
  "reason":"학생이 과제의 목적과 AI 사용 이유를 연결했습니다.",
  "unmetNextLevel":"최종 적용 검증 근거가 부족합니다.",
  "conditions":[{"key":"PURPOSE_L3","evidence":[{"sourceId":"turn:12:question","quote":"잔반을 줄일 방안을 비교해줘"}]}],
  "counterEvidence":[],"limitations":[]
}
```

- questionId는 실제 문항 ID이며 투명성만 `GLOBAL`입니다.
- criterion은 위5개 코드, criterionName은 표시 이름입니다. level은 정수0~4 또는null입니다.
- score는 `영역 maxScore × level / 4`, maxScore는 해당 영역의 원래 배점입니다. 문항 가중치를 적용한 최종 총점은 result.total을 사용합니다. 각 문항의 score를 그대로 모두 더하지 마세요.
- 자료 부족 level/score/total은null입니다.0점과 구분하세요. Cell.status는 RECOMMENDED/HOLD/TEACHER_REVIEWED입니다.
- Cell에 최상위 evidence 배열은 없습니다. 정방향 근거는 `conditions[].evidence[]`, 반대 근거는 `counterEvidence[]`입니다. 인용은 `{sourceId,quote}`입니다.
- Job.status는 PENDING/RUNNING/DONE/HOLD/NOT_APPLICABLE/FAILED, result.status는 DONE/HOLD/NOT_APPLICABLE입니다. PENDING/RUNNING/FAILED의 result는null일 수 있습니다.
- result.compliance.status는 **OK / CHECK / INSUFFICIENT_EVIDENCE**입니다. reason·evidence·limitations가 함께 있습니다. 교사의 최종 규정 판단은 별도로 CONFIRMED_OK/HOLD/CONFIRMED_VIOLATION입니다.

### 고정 근거 응답

GET `.../participants/{pid}/runs/{jobId}/evidence`:

```json
{
  "evaluationId":"evaluation-id","participantId":"participant-id","submissionHash":"제출 확인 해시",
  "title":"잔반 줄이기","aiPolicy":"HINT_ONLY",
  "policy":{"version":1,"rubricVersion":"LIT_PROCESS_RUBRIC_V1","taskGoal":"잔반 감소 방안","allowedRules":"힌트 허용","noAiPolicy":"HOLD","questionWeights":[{"questionId":"q1","weight":100}]},
  "questionIds":["q1"],
  "sources":[
    {"id":"task:q1","questionId":"q1","kind":"TASK","text":"방안과 근거를 설명하세요.","occurredAt":null,"answerVersion":null},
    {"id":"answer:q1:1","questionId":"q1","kind":"FINAL_ANSWER","text":"배식량을 선택하도록 제안한다.","occurredAt":"2026-11-07T09:00:00Z","answerVersion":2},
    {"id":"turn:12:question","questionId":"q1","kind":"STUDENT_QUESTION","text":"잔반을 줄일 방안을 비교해줘","occurredAt":"2026-11-07T08:55:00Z","answerVersion":1}
  ],
  "limitations":["외부 AI·저장되지 않은 입력·실제 외부 자료 열람은 자동 관측할 수 없습니다."]
}
```

sources.kind는 TASK/FINAL_ANSWER/STUDENT_QUESTION/AI_RESPONSE/ANSWER_CHANGE/WARNING/DISCLOSURE입니다. 시각은nullable 문자열, answerVersion은nullable 정수입니다. 이 입력은 분석 당시 고정본이며 이후 라이브 답안과 다를 수 있습니다. 정책 미설정이면 policy=null입니다.

## 4. 기존 루브릭·분석 규정 상태

`RubricScores.compliance.ai`와 `AssessmentAnalysis.compliance.result`의 가능한 문자열은 동일하게 **OK / CHECK / VIOLATION**입니다. 분석이 없으면 compliance.ai는null일 수 있습니다. VIOLATION도 자동 분석의 규정 검토 신호이며 교사 확정과 구분합니다. 기존 모델에는 INSUFFICIENT_EVIDENCE를 사용하지 않습니다. 과정 평가 모델의 상태값과 섞지 마세요.

## 5. 경고 이벤트 이름 호환

설정은 `FULLSCREEN_LEAVE`, `DEVTOOLS`를 정식 코드로 반환합니다. 프런트의 `FULLSCREEN`은 FULLSCREEN_LEAVE 별칭으로 수용·정규화합니다. 같은 배열에 별칭과 정식 코드가 함께 있으면 중복으로400 INVALID_ANTI_CHEAT입니다. 기존 TAB_LEAVE/PRINT_BLOCK 및 JSON 객체 호환은 유지합니다.

이벤트는 `FULLSCREEN_EXIT`, `FULLSCREEN_LEAVE`, `DEVTOOLS_OPEN` 모두 수용하며 보낸 이름으로 저장·반환합니다. 경고 수·activity.warnings·학생별 첫 경고 알림에 포함합니다. 호환 이름을 포함한 클라이언트10개 유형 전체가 참가자별60건/분을 공유합니다. 신고만으로 점수를 변경하지 않습니다.

## 6. 활동 카드 기여도

`activity.studentContribution`은 계속 **null**입니다. 실제 학생 저자 기여도를 추정했다고 표현하지 않습니다. 별도 분석의 `contribution.direct/aiReferenced/aiUsed`는 문장 유사도 기반 추정 비중이며 `basis=TEXT_SIMILARITY_ESTIMATE`입니다. 필요하면 이 값을 ‘문장 유사도 추정 비중’으로 별도 표시하고 실제 학생 기여도·부정행위 확률로 표기하지 마세요.

## 7. HWP·PDF

HWP는 저장 버전 다운로드 계약을 유지합니다. 리포트가 없으면404 **REPORT_NOT_FOUND**입니다. 다른 평가·참가자의 권한 오류(EVALUATION_NOT_FOUND/PARTICIPANT_NOT_FOUND 등)를 리포트 미생성으로 오인해 자동 생성하지 마세요. 프런트의 REPORT_NOT_FOUND일 때 POST reports→HWP GET 흐름을 그대로 사용할 수 있습니다.

- HWP Content-Type: `application/x-hwp`, Content-Disposition: `attachment; filename="lit-report-{reportId}-v{version}.hwp"`
- PDF Content-Type: `application/pdf`, Content-Disposition: `attachment; filename="lit-report-{reportId}-v{version}.pdf"`
- 기본 includeConversations=false, 버전 지정 다운로드는 해당 저장 버전 고정입니다.
- 교사용 `/report.pdf`만 version 생략 시 최신 리포트를 생성/재사용합니다. `/report/pdf` 및 HWP GET은 저장본 조회입니다.

## 8. 학생 leave 이후 본인 리포트

제출 기록이 있는 학생은 **leave 후 active=false 상태에서도 기존 participantToken으로 본인 리포트 POST/GET/PDF/HWP를 사용할 수 있습니다.** 활성 상태를 되살리지 않습니다. 미제출 inactive 학생·다른 평가의 토큰·잘못된 토큰은 허용하지 않습니다. 풀이/workspace/답안/AI/이벤트 권한은 기존 active 조건을 유지합니다.

제출→leave→리포트 생성/조회 흐름을 지원하며 토큰을 URL에 넣지 않습니다. API 응답은 본인 확정 제출본만 사용합니다.

## 9. AI 기본 정책

새 독립 평가 생성, 새 틀 생성 및 문항/설정 저장에서 정책을 생략하면 HINT_ONLY입니다. aiPolicy와 aiLevel을 함께 보내면 같은 단계여야 합니다. 명시적으로 지정한 기존5단계는 호환 유지합니다.

기존 평가·틀·진행 중 회차·제출 스냅샷은 **일괄 변경하지 않습니다.** 과거 조건·점수의 의미를 보존합니다. 기존 평가를 바꾸려면 수정 가능한 상태에서 교사가 명시적으로 HINT_ONLY로 변경해야 합니다. 시작된 회차의 잠금을 우회하지 않습니다.

## 10. 없는 API와 잘못된 메서드

인증·인가를 통과한 요청에서 없는 API 경로는404 `{code:"NOT_FOUND",message:...}`, 존재하는 경로의 잘못된 HTTP 메서드는405 `{code:"METHOD_NOT_ALLOWED",message:...}`와 Allow 헤더입니다. 의미가 다른 두 상태를 모두404로 바꾸지는 않습니다.

기존 401·403 인증/인가, 평가·학생·리포트 도메인의404 코드는 유지합니다. 예를 들어 미로그인 교사 경로는 없는 URL이어도 먼저401일 수 있습니다. 보안 필터가 허용하지 않은 namespace는403일 수 있습니다. 프런트는 인증 오류나 REPORT_NOT_FOUND를 ‘미배포 기능’으로 판단하지 마세요. 대기실 대체 흐름은 NOT_FOUND 또는 METHOD_NOT_ALLOWED처럼 경로/메서드 부재 응답에만 적용합니다.
