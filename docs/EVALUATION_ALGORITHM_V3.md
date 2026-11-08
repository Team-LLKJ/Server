# LIT 평가 알고리즘 V3

`feature/evaluation-algorithm-v3`의 로컬 구현입니다. 미커밋·미배포이며 Web 코드는 수정하지 않았습니다. 제공된 `message (13).txt` 최종안을 `src/main/resources/grading/process-rubric-v3.md`에 보존했습니다. 기존 Gemini 설정을 재사용하고 로컬 테스트는 대역으로 검증합니다.

## 적용 범위와 호환성

- 새 process-grading 정책·분석은 `LIT_PROCESS_RUBRIC_V3`입니다. 기존 V1(0~4단계), V2(1~5단계) 분석·교사 이력·보고서 버전은 보존합니다. 이전 버전의 진행 중 작업도 당시 루브릭으로 처리합니다.
- 기존 `/participants/{pid}/analysis`, `/rubric-scores`의 규칙 기반 0~4단계 API와 V3를 혼용하지 마세요. 새 화면은 아래 process-grading 분석을 사용합니다. 기존 API를 V3로 위장하거나 0~4단계를 1~5로 자동 변환하지 않습니다.
- `GET /api/teacher/evaluations/{id}/process-grading/rubric`: 정확한 항목 이름·15/25/20/25/15 배점·1~5단계·첨부 기준 전문입니다.
- `GET|PUT .../process-grading/policy`: 기존 필드 + `excludedCriteria?: ["CONTRIBUTION"]`. 기본은 빈 배열이고 중복은400 `INVALID_EXCLUDED_CRITERIA`입니다. 기존처럼 평가 시작 전에만 설정합니다. 문항 가중치 합100 규칙은 유지합니다.
- `POST .../process-grading/participants/{pid}/runs`: 제출 후202 비동기 예약. 제출 시 자동 예약도 유지합니다. 입력·분석·교사 수정·확정 이력은 별도 버전으로 저장합니다.
- `GET .../process-grading/participants/{pid}/analysis`: 최신 V3 분석·AI 추천·교사 결과·입력 경로·사용 목적을 함께 조회합니다. 기존 `/runs/{runId}`, `/evidence`, `/review`, `/review/history`, `/confirm`도 유지합니다.
- 교사 인증은 Bearer Access Token과 기존 평가/공유 권한, 학생 인증은 `X-Participant-Token`입니다. 관련 인증 설정은 변경하지 않았습니다.

## 점수·판단 보류·제외

점수는 서버가 `배점 × (단계 − 1) ÷ 4`로 계산합니다. 질문 횟수·길이·입력 경로 비율·문장 유사도로 가감하지 않습니다. 규정 준수는 `OK / CHECK / INSUFFICIENT_EVIDENCE`(준수 확인/위반 가능성/판단 보류)로 별도 표시하며 교사 판단으로 확정합니다.

자료 부족은 `HOLD`, 사전 제외는 `NOT_APPLICABLE`입니다. 단계·점수는 null이며 1단계·0점으로 바꾸지 않습니다. 제외 항목은 AI가 추천하거나 사후 override할 수 없습니다. AI 미관측 정책 `HOLD / NOT_APPLICABLE / USE_RECORDED_EVIDENCE`도 유지합니다.

`result.partialScore` 예시:

```json
{"assessedScore":60,"assessedMaxScore":75,"heldMaxScore":25,"notApplicableMaxScore":0}
```

이는 `확인된 항목60/75점 · 25점 판단 보류`입니다. `total`은 필수 평가 항목이 모두 채점된 경우에만 숫자입니다. 사전 제외가 있으면 남은 배점 그대로 합산하며100점으로 환산하지 않습니다. 모든 항목 제외 시 total=null입니다. 기존 확정 API는 보류 총점·규정 검토 미완료·오래된 제출본을 거절합니다.

## V3 평가 출력

`Result`는 기존 필드에 `summary`, `partialScore`를 추가합니다. `summary`는 핵심 답안→AI 활용→학생 판단·기여→보완점→한계 순서의4~6문장·한 문단입니다.

각 `Cell`은 기존 필드와 다음 `details`를 제공합니다:

```json
{
  "questionId":"q1","criterion":"CRITICAL_REVIEW","level":4,"score":15,
  "reason":"학생은 학교 자료와 AI 설명을 비교했습니다. 차이를 확인해 적용 가능성을 검토했습니다. 해당 기록을 실제 대조 단계의 근거로 판단했습니다.",
  "details":{
    "credits":[{"title":"실제 자료와 비교했습니다.","explanation":"학생은 학교 설문을 제시했습니다. AI 설명과 차이를 비교한 행동을 확인했습니다.","evidence":[{"sourceId":"turn:73:question","quote":"학교 설문과 비교해줘","start":0,"end":11,"location":{"questionId":"q1","conversationId":73,"messageRole":"USER","answerVersion":2,"textScope":"SOURCE_TEXT","start":0,"end":11}}]}],
    "deductions":[],
    "confirmationNeeded":["설문 원자료를 확보하지 못해 조사 방법은 확인하지 못했습니다."],
    "improvements":["설문 조사 방법과 표본을 함께 제시하세요."],
    "findings":[{"key":"CRITICAL_REVIEW_L4","status":"MET","essential":true,"explanation":"자료 대조 행동을 확인했습니다.","evidence":[{"sourceId":"turn:73:question","quote":"학교 설문과 비교해줘","start":0,"end":11}]}]
  }
}
```

위 예시는 구조를 설명하는 가상 데이터입니다. 실제 판단에는 전체 입력 기록이 필요합니다. 서버가 설명 문장의 존재만으로 위 예시의 의미를 검증했다고 간주하지 않습니다.

- `reason`은3~5문장, 가점·감점의 각 `explanation`은2~3문장입니다. 실제 근거가 없는 가점/감점 배열은 비웁니다. 축약·횟수 중심 설명은 사용하지 않도록 생성 지시를 제공합니다.
- `findings.status`는 충족 `MET`, 미충족 `UNMET`, 미확인 `UNKNOWN`입니다. 선택 단계 필수조건과 모순되거나 essential=true인 핵심 조건이UNKNOWN이면 보류합니다. 부정적인 낮은 단계 조건을 상위 단계의 선행 조건으로 요구하지 않습니다.
- 원문이 없거나 다른 문항의 인용, 모호한 반복 인용, 같은 인용의 반복 감점 설명, 잘못된 문장 형식은 검증에서 거절하고 관련 항목을 보류합니다. summary 형식 검증 실패는 전체 자동 추천을 보류합니다. 실패 사유는 `limitations`의 `VALIDATION_CODE` 등에 기록합니다.
- 구조·인용 위치·조건 정합성을 검사하지만 의미 판단의 정확성 자체는 교사 확인 대상입니다. 학생 기록은 데이터로만 전달하고 그 안의 채점 지시를 따르지 않도록 AI 지시문을 분리합니다.
- `analysisScope`: 대상 questionIds, answerVersions, conversationIds, missingData를 분석 입력에 고정합니다. 수정 이력·별도 참고 자료 원문을 확보하지 못한 경우 명시합니다.
- 종합 분석의 `aiRecommendation`은 원래 AI 결과, `teacherReview`는 교사 변경 이유·단계·확정 여부입니다. `criteria`는 현재 교사 반영 결과이며 문항별 단계가 다를 때 가상의 평균 단계를 생성하지 않습니다.
- `matchesCurrentSubmission=false`이거나 화면의 answerVersion과 인용 버전이 다르면 답안 구간 강조를 적용하지 마세요.

### 인용 위치

모든 인용은 실제 저장된 source.text의 부분 문자열인지 검사합니다. `Citation.start/end`는 source.text 기준 UTF-16·start포함/end제외입니다. 위치가 생략되고 원문에 딱 한 번 존재하면 서버가 계산합니다. 같은 문구가 여러 번 있으면 정확한 위치가 필요합니다.

`location`은 AI 입력값을 신뢰하지 않고 서버가 다시 생성합니다. 대화는 conversationId+messageRole, 최종 답안은 questionId+answerVersion+`textScope=FINAL_ANSWER_TEXT`와 위치로 연결합니다. 최종 답안 위치는 정규화 text 기준입니다. HTML 원문이나 별도 innerText에 적용하지 마세요.

## 편집 이력 저장

기존 `PUT /api/student/evaluations/{id}/questions/{questionId}/answer` (`/api/evaluations/...` 별칭)에 선택 필드 `inputOperations`를 추가합니다.

```json
{
  "content":"직접 붙여 AI","expectedVersion":0,"source":"DIRECT","aiRequestId":null,
  "inputOperations":[
    {"type":"DIRECT","start":0,"end":0,"text":"직접 ","conversationId":null},
    {"type":"PASTE","start":3,"end":3,"text":"붙여 ","conversationId":null},
    {"type":"AI_INSERT","start":6,"end":6,"text":"AI","conversationId":73}
  ]
}
```

작업은 배열 순서로 적용하며 각 start/end는 바로 이전 작업까지 반영된 정규화 문자열 기준입니다. 기준 버전은 expectedVersion입니다. DIRECT/PASTE/AI_INSERT는 `[start,end)`를 text로 교체합니다. DELETE는 text=""이며 FORMAT도 text=""이고 문자열·경로를 바꾸지 않습니다. UTF-16 서로게이트 중간 위치는 거절합니다.

AI_INSERT는 본인·해당 문항·완료된 대화의 conversationId와 삽입 문구가 일치해야 합니다. AI 응답은 서버의 정규화 텍스트로 비교합니다. 마크다운을 별도 방식으로 바꾼 문구는 일치하지 않을 수 있으므로 반환된 텍스트와 위치 계약을 지켜야 합니다. AI_INSERT 외 작업에 conversationId를 보내지 마세요.

재생 결과가 content를 안전 처리·정규화한 결과와 정확히 같을 때만 답안·이력을 한 트랜잭션으로 저장합니다. 최대500개 작업, text는 작업당100,000자·작업 전체1,000,000자 이하입니다. 잘못된 작업400 `INVALID_INPUT_OPERATION`, 결과 불일치400 `INPUT_TRACE_MISMATCH`, 과대 이력400 `INPUT_TRACE_TOO_LARGE`, 잘못된 AI 연결400 `INVALID_AI_INSERT_REFERENCE`, 저장 버전 충돌은 기존409 `ANSWER_VERSION_CONFLICT`입니다.

**기존 source=DIRECT와 PASTE 이벤트만으로 입력 경로를 확정하지 않습니다.** 입력 작업이 없는 구버전 저장은 호환하지만 변경된 답안의 경로는UNKNOWN으로 처리합니다. 정규화 문자열이 같은 서식 변경은 기존 경로를 유지합니다. 기존 PASTE 이벤트(start/end/answerVersion)는 참고 신고로 유지하며 그 이벤트만으로 새 입력 경로를 덮어쓰지 않습니다.

## 입력 경로 조회

- 학생: `GET /api/student/evaluations/{id}/questions/{questionId}/input-paths` (`/api/evaluations/...` 별칭): 해당 문항의 QuestionPath
- 교사: `GET /api/teacher/evaluations/{id}/students/{participantId}/input-paths`: 전체 InputPath View
- 교사 activity 응답에도 `inputPaths` 추가. process-grading 분석에는 해당 분석 시점의 inputPaths를 고정합니다.

```json
{
  "method":"EDIT_EVENT_REPLAY_V3","status":"PARTIAL",
  "questions":[{
    "questionId":"q1","answerVersion":2,"text":"가나 😀","status":"PARTIAL",
    "segments":[{"start":0,"end":2,"type":"DIRECT"},{"start":3,"end":5,"type":"UNKNOWN"}],
    "shares":[{"type":"DIRECT","colorKey":"blue","characters":2,"percentage":66.7},{"type":"AI_INSERT","colorKey":"orange","characters":0,"percentage":0},{"type":"PASTE","colorKey":"yellow","characters":0,"percentage":0},{"type":"UNKNOWN","colorKey":"gray","characters":1,"percentage":33.3}],
    "nonTextElements":0,"clientReported":true
  }],
  "shares":[],"limitations":[]
}
```

View.shares는 모든 문항 합계입니다(위 예시는 생략). 문항은 COMPLETE/PARTIAL, 전체도 일부UNKNOWN이면PARTIAL입니다. type=DIRECT/AI_INSERT/PASTE/UNKNOWN입니다. 공백·줄바꿈·삭제된 내용은 제외하고 **남은 유니코드 코드포인트 수**로 계산합니다(이모지 하나를UTF-16 두 글자로 과대 집계하지 않음). 위치는UTF-16입니다. 분모가 있으면0.1% 최대 잔여 배분으로 합100%, 빈 텍스트면 모두0입니다. nonTextElements는 별도 개수입니다. clientReported=true는 브라우저 신고임을 뜻하며 실제 저자·외부 출처를 증명하지 않습니다.

처음에는 강조하지 않고 유형 클릭·토글·전체 표시·문항 전환을 구현하는 것은 프런트 역할입니다. 평가 인용 강조와 입력 경로 강조는 다른 표시 모드로 처리하세요. Web 브랜치는 수정하지 않았습니다.

## AI 사용 방법

- `GET /api/teacher/evaluations/{id}/students/{participantId}/usage-analysis`
- 기존 activity에 `usageAnalysis` 추가, `usage`는 같은 V3 비율을 name/percentage/level=null/colorKey 형식으로 제공합니다. 기존의 비율 기반 높음/보통/낮음은 제거합니다. `unclassifiedCount`는 호환용0이며 기타를 미분류로 숨기지 마세요.
- V3 process-grading 분석의 `purposeUsage`는 분석 입력 당시 결과입니다. 옛 aiUsage/textSimilarity 필드는 과거 호환용이며 입력 경로로 사용하지 마세요. V3 aiUsage는 빈 배열이고 purposeUsage를 사용합니다.

목적은 IDEATION(아이디어 탐색), CONCEPT(개념 이해), RESEARCH(자료 조사), STRUCTURING(구조화), GENERATION(답안 생성), CRITICAL_REVIEW(비판적 검토), EXPRESSION(표현 수정), OTHER(기타)입니다. 현재 분류기는 질문 표현의 키워드 규칙(`KEYWORD_MULTI_LABEL_V3`)입니다. 의미 정확성을 보장하지 않으며 점수로 쓰지 않습니다.

질문 하나가 n개 목적에 해당하면 각각1/n을 부여하고 질문의 총가중치를1로 유지합니다. 유효 질문의 가중치 합을 분모로0.1% 비율을 계산합니다. `usage[]`에 purpose/name/percentage/representativeQuestions, `questions[]`에 conversationId/questionId/quote/purposes/weightPerPurpose를 제공합니다.

상태는 READY, NO_RECORDS(질문 없음), PARTIAL(누락·중복·미완료 응답 포함), RECORDS_UNAVAILABLE(대화 자료를 확보하지 못함)입니다. 실제 DB·네트워크 조회 오류는HTTP 오류이며 NO_RECORDS로 바꾸지 않습니다. 대표 질문과 대화 링크를 함께 제공하며 비율은 점수·역량 단계가 아닙니다.

## PDF·HWP

새 보고서는 해당 평가에 process-grading 사전 정책이 있으면 최신 V3 분석과 교사 검토를 고정합니다. 종합평가 문단, 기준별 AI 추천·교사 검토, 평가 이유·가점·감점·인용·확인 필요·개선 방향, 입력 경로와 목적별 비율을 공통 레이아웃에 반영합니다. 기존 저장 보고서는 소급 변경하지 않으며 새 결과 반영에는 기존 POST reports로 새 버전을 생성합니다. 정책이 없는 기존 평가는 종전 보고서 흐름을 유지합니다.

채점 답안 응답의 questions[].minChars/maxChars를 추가했습니다. 제한 없으면null, 제출 후에는동결된제출문항기준입니다. 구간·붙여넣기·SSE·추방의정확한연동은 [프런트 계약](FRONTEND_INTEGRATION_REFERENCE.md)을참고하세요.
