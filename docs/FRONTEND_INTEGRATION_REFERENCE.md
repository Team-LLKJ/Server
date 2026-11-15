# 프런트 연동용 정확한 계약: 구간·붙여넣기·제한·SSE·추방

기준: `feature/participant-expulsion` 로컬 구현·미커밋·미배포. 이 브랜치는 앞선 V3 변경과 채점 답안의 글자 수 제한 보완을 포함합니다. Web 저장소는 수정하지 않았습니다. 시트 요약 대신 사용할 수 있도록 응답 위치·정확한 필드·예외를 정리합니다.

## 1. contribution.segments는 유사도 구간입니다

교사 Bearer 인증:

`GET /api/teacher/evaluations/{evaluationId}/participants/{participantId}/analysis`

이는 기존 `LIT_RULE_BASED_EXPLAINED_V2` 분석입니다. V3 의미 기반 채점과 구분하세요. 실제 추가 필드는 다음과 같습니다(부분 응답 예시).

```json
{
  "participantId":"participant-id",
  "status":"DONE",
  "model":"LIT_RULE_BASED_EXPLAINED_V2",
  "contribution":{
    "direct":100,"aiReferenced":0,"aiUsed":0,
    "basis":"TEXT_SIMILARITY_ESTIMATE",
    "segments":[{
      "questionId":"q1","start":0,"end":5,"type":"STUDENT",
      "similarity":0.1,"effectiveSimilarity":0.1,"estimated":true,"answerVersion":2
    }]
  },
  "answerTexts":[{"questionId":"q1","text":"학생 답안","answerVersion":2}],
  "pasteEvents":[{
    "eventId":123,"questionId":"q1","occurredAt":"2026-11-08T08:00:00Z",
    "length":5,"start":0,"end":5,"answerVersion":2,
    "matchesFinalAnswer":true,"clientReported":true
  }]
}
```

| 필드 | 타입·정의 |
|---|---|
| segments | 배열. 답안이 비었거나 과거 데이터에 없으면[] 가능 |
| questionId | 문항 ID 문자열 |
| start / end | 정수, 해당 answerTexts.text의UTF-16 인덱스. start포함/end제외, JS String.slice와 동일 |
| type | STUDENT / AI_REFERENCED / AI_USED |
| similarity | 원본 AI 응답과의 유사도0~1 |
| effectiveSimilarity | 동일 문항 AI_ANSWER_COPY 신호의보정까지 적용한0~1 |
| estimated | 항상true. 실제 저자를 확정하지 않음 |
| answerVersion | 정수, 분석 대상 서버 답안 버전 |

`STUDENT` 코드는 낮은 유사도 분류이지 학생의 실제 직접 작성 증명이 아닙니다. 기본 기준은 <.4 / [.4,.8) / >=.8이고 환경의 실제 값은 `GET /api/teacher/evaluations/{id}/analysis/definition` 또는 저장 분석의 definition을 사용하세요. ‘직접 입력/AI 삽입/붙여넣기’ 필터에는 이 segments를 쓰지 마세요. 그것은 아래 V3 inputPaths입니다.

text는 서버가 HTML 제거·문단LF·표 셀` | `로 정규화한 문자열입니다. HTML 원문이나 프런트가 별도로 만든 innerText에 이 좌표를 대입하지 마세요. 문항과 버전이 모두 일치할 때만 강조합니다.

```js
const answer = analysis.answerTexts.find(x => x.questionId === selectedQuestionId);
const ranges = answer && answer.answerVersion === displayedAnswerVersion
  ? analysis.contribution.segments.filter(x =>
      x.questionId === selectedQuestionId && x.answerVersion === answer.answerVersion)
  : [];
// answer.text.slice(range.start, range.end)를 기준으로 표시합니다.
```

## 2. pasteEvents는 붙여넣기 신고입니다

배열은 위 analysis.pasteEvents 및 아래 activity.pasteEvents에서 같은 형식으로 제공됩니다.

`GET /api/teacher/evaluations/{evaluationId}/students/{participantId}/activity`

| 필드 | 타입·주의점 |
|---|---|
| eventId | 숫자, 학습 이벤트 ID |
| questionId | 문자열 또는null(구버전 신고) |
| occurredAt | ISO8601 문자열. 기기가 신고한 시각, 없으면 서버 기록 시각 |
| length | 정수 또는null |
| start / end / answerVersion | 정수 또는null. 구간 없는 기존 신고는null |
| matchesFinalAnswer | boolean. 최종 답안과 버전·범위가 맞는지 확인한 값 |
| clientReported | boolean. 기기 신고임을 표시하며 실제 외부 출처·부정행위 확정이 아님 |

강조는 같은 문항, 같은 답안 버전, matchesFinalAnswer=true, start/end가숫자이고end>start일 때만 허용하세요. length만으로 위치를 추정하거나 버전이 바뀐 뒤 옛 구간을 표시하지 마세요. 이후 수정으로 글자가 이동한 경우를 자동 추측하지 않습니다.

전송은 기존 학생 이벤트 API입니다:

```http
POST /api/student/evaluations/{evaluationId}/events
X-Participant-Token: <participantToken>
Content-Type: application/json
```

```json
{"type":"PASTE","occurredAt":"2026-11-08T08:00:00Z","questionId":"q1","length":5,"start":0,"end":5,"answerVersion":2}
```

정확한 범위를 보내려면 답안 저장 성공 뒤 그 버전과 정규화 텍스트 위치를 사용하세요. start/end/answerVersion은 함께 보내야 합니다. 부분 필드·범위 오류400 INVALID_PASTE_RANGE, 버전 충돌409 PASTE_VERSION_CONFLICT입니다. PASTE는 경고가 아니며 기존12종 합산60건/분 제한입니다.

## 3. V3 실제 입력 경로는 inputPaths입니다

- `GET /api/teacher/evaluations/{id}/students/{pid}/input-paths`
- `GET /api/student/evaluations/{id}/questions/{qid}/input-paths`
- activity.inputPaths 및 process-grading 분석.inputPaths에도 포함됩니다.

교사 응답의 questions[]에 questionId/answerVersion/text/status/segments/shares/nonTextElements/clientReported가 있습니다. segments는 `{start,end,type}`이고 type=DIRECT / AI_INSERT / PASTE / UNKNOWN입니다. 학생 API는 해당 questions 항목 하나를 반환합니다.

비율은 편집 작업을 순서대로 재생하여 최종 답안에 남은 글자 수로 계산합니다. 공백·줄바꿈·삭제 제외, 글자 수는 유니코드 코드포인트, 좌표는UTF-16입니다. 색상은blue/orange/yellow/gray입니다. 새 `SaveAnswer.inputOperations`를 프런트에서 전송해야 하며 이력이 없는 기존 답안은UNKNOWN입니다. 단순 source=DIRECT 또는 붙여넣기 신고로 출처를 확정하지 않습니다. 전체 정확한 요청은 `EVALUATION_ALGORITHM_V3.md`를 참고하세요.

## 4. 채점 답안의 minChars / maxChars

이번에 아래 두 응답의 **questions[] 바로 아래**에 추가했습니다. 제한이 없거나 과거 스냅샷에 정보가 없으면null입니다.

```http
GET /api/teacher/evaluations/{id}/students/{pid}/answers
GET /api/teacher/evaluations/{id}/participants/{pid}/answers
Authorization: Bearer <accessToken>
```

```json
{
  "questions":[{
    "questionId":"q1","position":1,"title":"문항","content":"문제 내용",
    "minChars":2,"maxChars":20
  }]
}
```

위 예시는 공통 필드만 발췌한 것으로, 기존 answer 또는 answerHtml/paragraphs 등의 응답은 그대로 유지됩니다. 제출 후에는 해당 제출 시점 문항 제한을 사용합니다. 현재 템플릿 설정을 대신 끼워 넣지 않습니다.

배포 전 코드에서도 `GET /api/teacher/evaluations/{id}/questions`의 questions[].minChars/maxChars 및 학생 workspace.questions[].question에는 제한 값이 있습니다. 전용 채점 응답의 누락은 이번 브랜치에서 보완한 사항이므로 운영 적용 전에는 설정 조회와questionId를 연결해야 합니다.

## 5. 교사 실시간 SSE

시트1의133행, 요청·응답 예시123행에 있습니다. 구현은 브랜치에 있고 운영 배포 전입니다.

```http
GET /api/teacher/evaluations/{evaluationId}/live-events?afterId=0
Authorization: Bearer <accessToken>
Accept: text/event-stream
Last-Event-ID: 122
```

Last-Event-ID는 선택이며 있으면afterId보다 우선합니다. 둘 다0이상입니다. 토큰을 URL에 넣지 마세요. 기본 EventSource는Authorization 헤더를 설정할 수 없으므로 fetch 기반SSE 읽기를 사용하세요.

```text
id: 123
event: warning
data: {"eventId":123,"participantId":"pid","nickname":"학생","type":"WINDOW_LEAVE","occurredAt":"2026-11-08T08:00:00Z"}

id: 124
event: submission
data: {"eventId":124,"participantId":"pid","nickname":"학생","type":"SUBMITTED","occurredAt":"2026-11-08T08:01:00Z"}

id: 125
event: participant-expelled
data: {"eventId":125,"participantId":"pid","nickname":"학생","type":"PARTICIPANT_EXPELLED","occurredAt":"2026-11-08T08:02:00Z"}
```

추방 이벤트는 이번 브랜치에서 추가한 것으로 경고가 아닙니다. 이 데이터의occurredAt은 서버 기록 시각입니다. 영속 eventId로 재생하고 중복을 제거하세요. 기본afterId=0은 과거 기록도 반환합니다. 서버1초 확인·한번200개·20초keepalive·120초연결 후재접속, 계정10/전체200 연결 한도(429 SSE_LIMIT)입니다. 토큰 만료·현재 평가/공유 권한 상실 시 연결이 닫힙니다. 기존 폴링도 유지합니다.

## 6. 참여자 추방

이번에 새로 추가했습니다. 기존 학생 leave(자발적 퇴장·재접속 가능)와 구분합니다.

```http
DELETE /api/teacher/evaluations/{evaluationId}/participants/{participantId}
Authorization: Bearer <accessToken>
```

본문 없음, 성공204(본문 없음)입니다. 같은 대상의 재요청도204이며 추방 시각·이벤트는 중복 생성하지 않습니다. 평가 소유 교사, 공유 틀의기존OWNER/EDITOR 범위만 허용합니다. 다른 평가 참가자404 PARTICIPANT_NOT_FOUND, 다른교사평가404 EVALUATION_NOT_FOUND, 인증없음401입니다.

- 대기·진행 중 추방 가능. 종료·시간 만료 후 새 추방은409 EVALUATION_ENDED입니다. 이미 추방한 대상의 반복 요청은 종료 후에도204입니다.
- active=false, expelled_at을 기록하고 답안·대화·제출·채점·리포트는 삭제하지 않습니다. 참가자 대기실 목록에서는 제외하고 채점 학생 목록에는 남깁니다.
- 학생 목록/상세와submissions의 각 학생에 expelled:boolean, expelledAt:ISO8601|null을 제공합니다. 기존presence/status enum은 유지합니다(presence=LEFT와expelled=true로 구분).
- 해당 토큰의 state/workspace/답안/AI/이벤트/안내동의/보고서 및 PIN join의토큰재접속·별도 reconnect를403 PARTICIPANT_EXPELLED로 차단합니다. 프런트는 자동 재접속을 중단하고 추방 안내를 표시하세요. 초안·서버 기록을 삭제할 필요는 없습니다.
- 진행 중 AI를FAILED/PARTICIPANT_EXPELLED로 마감하여 뒤늦은 성공 응답이 저장되지 않게 합니다. 추방 자체는 경고·자동 감점이 아닙니다.
- 추방 즉시 제출을 만들지 않습니다. 평가 종료 시에는 기존의 미제출 자동 제출 정책을 따릅니다.
- 같은 평가의 닉네임을 예약 상태로 보존하여 같은 닉네임 신규 참가를409 NICKNAME_TAKEN으로 막습니다. 비회원 참여이므로 다른 닉네임으로 들어오는 동일인까지 식별·차단하는 기능은 아닙니다.
- 추방 해제 API는 이번 범위에 없습니다. 자발적 leave의 정상 재접속·미추방 제출자의 퇴장 후 보고서 조회는 유지합니다.

학생 차단 응답:

```json
{"code":"PARTICIPANT_EXPELLED","message":"교사에 의해 참여가 종료되어 다시 접속할 수 없습니다."}
```

## 7. 시트 전체 원문 파일

함께 제공하는 `full-api-spec.md`와 `full-api-spec.json`은4개 탭의 모든 채워진 행을 행 번호와 함께 내보낸 원문입니다. 요청·응답 예시/공통 규칙/오류 코드 탭을 요약으로 대체하지 않았습니다. 행별 개발·배포 상태를 구분하세요. 이 문서의 새 기능도 현재 운영에 적용됐다는 뜻은 아닙니다.

## 백엔드 계약

- 수행평가 상세와 목록의 `isOwner`는 호출자의 소유 여부입니다.
- 참가 닉네임은 1~20자입니다. 꺾쇠나 제어 문자는 400 `INVALID_NICKNAME`으로 거절합니다.
- 문항 `title`이 비어 있으면 `문항 {position}`으로 채웁니다.
- AI 활용 분석의 `indicators[].reasons[].kind`는 `ADD` 또는 `DEDUCT`입니다. `requiredRecords[]`는 `{name,done}`을 제공합니다.
- `sentences[].sourceStatus`는 `AI`, `DIRECT`, `UNCERTAIN`입니다. `closestAiSentence`는 비교 근거가 없으면 null입니다.
- `POST /api/teacher/evaluations/{id}/extend`는 `{additionalMinutes}`(1~600)를 받고 갱신된 로비의 `endsAt`을 반환합니다.
- 학생 정책 확인은 `POST /api/student/evaluations/{id}/policy-acceptance`에 `X-Participant-Token`과 `{policyVersion}`을 보냅니다. `workspace`의 `policyAccepted`와 `policyVersion`으로 확인 상태를 조회합니다.
- 새 루브릭 기준은 AI 활용 목적 20점, 자기 주도적 사고 30점, 비판적 검토 20점, 학생 기여 30점입니다. 과정 채점 결과의 `criterionName`과 `maxScore`를 사용하세요.
- HWP를 버전 지정 없이 조회하면 제출 기록이 있고 저장 보고서가 없는 경우 생성합니다. 저장 버전을 지정하면 해당 버전을 조회합니다.
- 교사 가입은 약관 동의 버전 필드를 요구합니다. 반 대기실은 `POST /api/teacher/assessments/{id}/classes/{classId}/lobby`로 엽니다. 학생 복귀는 `POST /api/participation/reconnect`를 사용합니다.
- 교사 SSE는 `GET /api/teacher/evaluations/{id}/live-events`이며 Bearer 인증이 필요합니다. 루브릭 모델 표는 교사 판정 근거 확인에 사용할 수 있습니다.
