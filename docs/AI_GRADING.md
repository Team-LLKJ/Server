> 신규 행동 근거 기반 V1 모델은 [PROCESS_GRADING.md](PROCESS_GRADING.md)를 참고하세요. 아래 문서의 기존 키워드 모델과 API는 호환용으로 유지되며 신규 모델의 점수로 자동 대체되지 않습니다.

> 작업 상태: 압축본을 검증·수정한 로컬 백엔드 초안입니다. 커밋 승인 대기이며 새 기능은 아직 배포하지 않았습니다. 프런트 코드는 포함되지 않았습니다.

# AI 활용 채점 API·알고리즘 (#18)

프런트 요청 문서 "LIT 백엔드 API 정리"의 2장(채점 화면)과 7장(평가 알고리즘)을 구현한 내용입니다.
모든 경로는 `/api/teacher/evaluations/{id}` 기준이며 소유 교사의 `Authorization: Bearer {accessToken}`이 필요합니다.
다른 교사의 평가·다른 평가의 참가자는 404, 인증 누락은 401, 오류 본문은 기존 `{ code, message }`입니다.

> 기존 `/students/{participantId}/...` 채점 API(0~20점 임시 루브릭, PR #17)는 호환을 위해 그대로 둡니다.
> 새 채점 화면은 이 문서의 `/participants/{participantId}/...` API를 사용하세요. 두 점수 저장소는 서로 영향을 주지 않습니다.

## 1. API 목록

| 기능 | 메서드 · 경로 | 비고 |
|---|---|---|
| 학생별 제출 현황 | `GET /submissions?page=0&size=100&query=` | size 최대 200, query는 닉네임 부분 일치 |
| 학생 답안(문단 단위) | `GET /participants/{pid}/answers` | 제출 전 최신 저장본, 제출 후 확정 snapshot |
| 학생 AI 대화 | `GET /participants/{pid}/conversations?questionId=&page=0&size=200` | 질문 유형 포함 |
| AI 활용 분석 | `GET /participants/{pid}/analysis` | 미제출은 `status: NOT_SUBMITTED` |
| 분석 재실행 | `POST /analysis/run` | 202, 제출 완료 학생 전체를 PENDING으로 예약 |
| 루브릭 조회 | `GET /rubric` | 기본 5개 기준 |
| 루브릭 변경 | `PUT /rubric` | 합계 100점, 채점 시작 전만 |
| 루브릭 기본값 복원 | `DELETE /rubric` | 채점 시작 전만 |
| 기준별 점수 조회 | `GET /participants/{pid}/rubric-scores` | AI 추천 + 교사 값 |
| 기준 하나 저장 | `PUT /participants/{pid}/rubric-scores/{criterionId}` | 자동 저장용, 재시도 안전 |
| 점수 변경 이력 | `GET /participants/{pid}/rubric-scores/history?afterId=0&limit=100` | |
| 채점 확정 | `POST /participants/{pid}/grade/confirm` | 모든 기준 입력 시만 |
| 허용 범위 교사 판단 | `PUT /participants/{pid}/compliance-review` | 판단 보류 등 |

PDF 저장은 프런트의 브라우저 인쇄를 쓰므로 서버 API를 추가하지 않았습니다.

## 2. 제출 현황

`GET /api/teacher/evaluations/{id}/submissions`

```json
{
  "evaluationId": "평가 UUID",
  "items": [{
    "participantId": "학생 UUID", "name": "이하랑", "nickname": "이하랑",
    "status": "WARNING", "submitted": true, "submissionStatus": "SUBMITTED",
    "submittedAt": "2026-10-31T01:00:00Z", "submissionMode": "MANUAL",
    "warningCount": 2, "gradingStatus": "NOT_STARTED",
    "totalScore": 51.25, "totalAi": 51.25, "compliance": "VIOLATION", "analysisStatus": "DONE"
  }],
  "page": 0, "size": 100, "totalElements": 3, "totalPages": 1,
  "counts": { "submitted": 1, "warning": 1, "writing": 1, "confirmed": 0 }
}
```

- 학생은 계정 없이 닉네임으로 참여하므로 `name`은 닉네임과 같은 값입니다.
- `status`: 경고 이벤트가 1건 이상이면 `WARNING`, 아니면 제출 시 `SUBMITTED`, 그 외 `WRITING`. counts의 세 값은 겹치지 않으며 합이 totalElements입니다.
- `warningCount`: WINDOW_LEAVE·SHORTCUT_CAPTURE·WINDOW_BLUR·CAPTURE_SHORTCUT·COPY_ATTEMPT·FULLSCREEN_EXIT·DEVTOOLS_OPEN 건수. PASTE·AI_ANSWER_COPY는 경고가 아니라 채점 신호라 세지 않습니다.
- `totalScore`: 확정(`CONFIRMED`)이면 확정 총점, 아니면 AI 추천 총점. 미제출은 null.
- `gradingStatus`: `NOT_STARTED`(교사 점수 없음) / `DRAFT`(일부 저장 또는 확정 뒤 수정) / `CONFIRMED`.
- `submissionMode`: `MANUAL` / `AUTO_DEADLINE` / `AUTO_TEACHER_END`.

## 3. 답안과 문단 출처

`GET .../participants/{pid}/answers`

```json
{
  "participantId": "학생 UUID", "nickname": "이하랑", "status": "SUBMITTED",
  "submittedAt": "2026-10-31T01:00:00Z", "submissionMode": "MANUAL", "sourcesEstimated": true,
  "questions": [{
    "questionId": "문항 UUID", "position": 2, "title": "문항 2", "content": "예시를 드세요.",
    "answerHtml": "<p>…</p><p>…</p>", "version": 1, "updatedAt": "2026-10-31T00:58:00Z", "declaredSource": "AI_USED",
    "paragraphs": [
      { "id": "q2-p1", "questionId": "문항 UUID", "questionPosition": 2, "text": "광합성은 …", "source": "AI_USED",
        "similarity": 1.0, "changedRatio": 0.0, "conversationId": 41, "estimated": true, "declaredSource": "AI_USED" },
      { "id": "q2-p2", "text": "학교 화단의 나팔꽃이 …", "source": "DIRECT", "similarity": 0.0, "changedRatio": 1.0, "conversationId": null }
    ]
  }]
}
```

- 문단은 HTML의 `p, li, h1~h6, blockquote, pre, tr`(표는 행 단위 `셀 | 셀`)와 `<br>` 줄바꿈 기준입니다. 일반 텍스트는 줄 단위입니다.
- `source`는 서버 추정값입니다(`estimated: true`). 화면에 "추정"으로 표시해 주세요. `declaredSource`는 학생 화면이 저장 시 보낸 값입니다.
- `answerHtml`은 저장된 정리 HTML 그대로입니다. 렌더링 시 기존처럼 안전한 HTML 처리를 유지하세요.

## 4. AI 대화

`GET .../participants/{pid}/conversations`

```json
[{ "conversationId": 41, "requestId": "r3", "questionId": "문항 UUID",
   "question": "답안을 대신 작성해줘", "answer": "…", "status": "COMPLETED", "errorCode": null,
   "createdAt": "2026-10-31T00:50:00Z", "completedAt": "2026-10-31T00:50:02Z",
   "questionType": "ANSWER_DRAFTING", "questionTypeLabel": "답안 대리 작성 요청" }]
```

질문 1건 = 항목 1개(질문·응답 쌍)입니다. status는 PENDING/COMPLETED/FAILED. 대기·실패 응답은 answer=null입니다.

## 5. AI 활용 분석

`GET .../participants/{pid}/analysis`

```json
{
  "participantId": "학생 UUID", "status": "DONE", "model": "LIT_RULE_BASED_RUBRIC_V1", "provisional": true,
  "aiPolicy": "HINT_ONLY", "aiLevel": 2,
  "compliance": { "result": "VIOLATION", "reasons": ["허용 단계(Level 2) 대비 AI 문장을 그대로 사용한 비율이 높습니다 (1문단, 55%)", "답안 대리 작성 요청 1건"] },
  "contribution": { "direct": 45, "aiReferenced": 0, "aiUsed": 55, "basis": "TEXT_SIMILARITY_ESTIMATE" },
  "usage": [
    { "type": "CONCEPT_UNDERSTANDING", "label": "개념 이해", "count": 1, "share": 0.333, "level": "MEDIUM" },
    { "type": "ANSWER_DRAFTING", "label": "답안 대리 작성 요청", "count": 1, "share": 0.333, "level": "MEDIUM" }
  ],
  "questions": [{ "conversationId": 41, "requestId": "r3", "questionId": "문항 UUID", "type": "ANSWER_DRAFTING", "label": "답안 대리 작성 요청", "method": "RULE" }],
  "paragraphs": [{ "id": "q2-p1", "source": "AI_USED", "conversationId": 41, "…": "3장과 같은 구조" }],
  "indicators": [{ "indicator": "PURPOSE", "value": 0.0, "level": 0, "reason": "질문 3개 중 1개가 …", "evidence": [{ "conversationId": 41, "paragraphId": null, "eventId": null, "note": "답안 대리 작성 요청" }] }],
  "signals": { "aiQuestions": 3, "completedResponses": 3, "pasteEvents": 1, "aiCopyEvents": 1, "warningEvents": 2,
               "disclosedTools": true, "disclosedUsedParts": true, "disclosedReason": true, "aiUsed": true },
  "summary": "AI 질문 3건 중 1건이 개념 이해·자료 탐색·아이디어 구체화 목적이었습니다. 최종 답안은 …",
  "analyzedAt": "2026-10-31T01:00:03Z",
  "limitations": ["AI 추천 점수이며 최종 점수는 교사가 확정합니다.", "…"]
}
```

- 분석은 **서버가 실행**하고 결과를 저장합니다. 제출(수동·자동) 직후 PENDING으로 예약되고 백그라운드 작업이 3초 간격으로 처리합니다.
  조회 시 아직 PENDING이면 그 자리에서 계산해 DONE으로 돌려주므로, 실제로는 대부분 바로 DONE입니다. status가 PENDING이면 몇 초 뒤 다시 조회하세요.
- `contribution`은 관측된 답안과 내부 AI 응답의 **낮음/부분/높음 유사도 분류 비율**입니다(`basis: TEXT_SIMILARITY_ESTIMATE`). 실제 학생 저자 기여도가 아니며, 기존 `studentContribution`은 계속 null입니다. 프런트의 기여도 카드는 빼거나 측정 불가로 표시하세요. 답안이 비면0/0/0입니다.
- `usage.level`: 질문 비중 50% 이상 HIGH, 20% 이상 MEDIUM, 그 외 LOW. 0건인 유형은 내려가지 않습니다.
- 질문 유형: `CONCEPT_UNDERSTANDING`(개념 이해), `RESEARCH`(자료 탐색), `IDEA_EXPLORATION`(아이디어 구체화), `ANSWER_DRAFTING`(답안 대리 작성 요청), `VERIFICATION`(검증 요청), `OTHER`(기타).
- `compliance.result`: OK / CHECK / VIOLATION. **점수를 자동으로 깎지 않습니다.** 교사는 `compliance-review`로 판단을 남깁니다.

## 6. 루브릭

`GET /rubric`

```json
{
  "evaluationId": "평가 UUID", "totalScore": 100, "custom": false, "locked": false, "model": "LIT_RULE_BASED_RUBRIC_V1",
  "levels": [{ "level": 0, "label": "매우 못함" }, { "level": 1, "label": "못함" }, { "level": 2, "label": "보통" }, { "level": 3, "label": "잘함" }, { "level": 4, "label": "매우 잘함" }],
  "criteria": [
    { "criterionId": 1, "name": "AI 활용 목적의 적절성", "maxScore": 15, "description": "AI를 답 생성이 아니라 학습·탐색·이해를 위해 썼는가",
      "levelDescriptions": ["…0단계", "…1", "…2", "…3", "…4단계"], "indicator": "PURPOSE", "basis": "프로젝트 제안 초안·교사 확인 필요" }
  ]
}
```

기본 제안안(미확정, 평가별 수정 가능): ① 목적 적절성 15 · ② 자기 주도적 사고 25 · ③ AI 답변의 비판적 검토 20 · ④ 최종 답안 기여 25 · ⑤ 과정 투명성 15 (합계 100).

`PUT /rubric` — **평가마다 바꿀 수 있습니다**(7-7 5번 답).

```json
{ "criteria": [
  { "name": "AI 활용 목적의 적절성", "maxScore": 20, "indicator": "PURPOSE", "description": "", "levelDescriptions": ["", "", "", "", ""] },
  { "name": "발표", "maxScore": 80, "indicator": "MANUAL" }
] }
```

- 기본 루브릭은 provisional=true인 프로젝트 제안안이며 공식 표준을 뜻하지 않습니다. 교사가 지정하면 custom=true/provisional=false입니다.

- 배점 합계가 100이 아니면 400 INVALID_RUBRIC. 기준은 1~10개, criterionId는 보낸 순서대로 1부터 다시 매깁니다.
- `indicator`: PURPOSE / SELF_DIRECTION / CRITICAL_REVIEW / CONTRIBUTION / TRANSPARENCY는 해당 지표로 AI 추천 점수를 받고, MANUAL은 AI 추천 없이 교사만 채점합니다.
- 해당 평가에서 교사 점수가 하나라도 저장되면 `locked: true`가 되고 PUT·DELETE는 409 RUBRIC_LOCKED입니다.
- 평가 생성 화면에서 기준 입력이 꼭 필요하지는 않습니다. 생략하면 기본 5개가 적용됩니다.

## 7. 기준별 점수와 확정

`GET .../participants/{pid}/rubric-scores`

```json
{
  "participantId": "학생 UUID",
  "criteria": [{
    "criterionId": 1, "name": "AI 활용 목적의 적절성", "maxScore": 15, "indicator": "PURPOSE",
    "ai": { "level": 3, "score": 11.25, "reason": "질문 8개 중 6개가 개념 이해·자료 탐색", "evidence": [{ "conversationId": 41 }] },
    "teacher": { "level": 3, "score": 11.25, "feedback": "", "updatedAt": "2026-10-31T02:00:00Z" },
    "adjusted": false
  }],
  "totalAi": 56.25, "totalTeacher": 75.0, "totalCurrent": 75.0,
  "recommendedMaxScore":75,"missingAiCriteria":["최종 답안에 대한 학생의 기여"],
  "status": "DRAFT", "confirmedTotal": null, "confirmedAt": null,
  "analysisStatus": "DONE",
  "compliance": { "ai": "CHECK", "decision": null, "note": null }
}
```

- `teacher`는 교사가 저장하기 전에는 **null**입니다. 화면에서 기본값으로 AI 추천을 보여 주고 싶으면 `ai`를 사용하세요.
- `totalTeacher`는 모든 기준에 교사 값이 있을 때만 숫자, 아니면 null. `totalCurrent`는 기준별 (교사 값 ?? 추천 ?? 0)의 **부분합**이며 최종 성적이 아닙니다. `totalAi`도 부분합입니다. `recommendedMaxScore`가 추천 가능한 배점 합계, `missingAiCriteria`가 추천 불가 항목입니다. 기여도25점은 교사 입력이 필요합니다.
- `adjusted`: 교사 수준이 AI 추천 수준과 다르면 true.
- 미제출 학생은 `analysisStatus: NOT_SUBMITTED`, `ai: null`입니다.

`PUT .../participants/{pid}/rubric-scores/{criterionId}`

```json
{ "level": 3, "feedback": "질문 목적이 대체로 적절합니다." }
```

- 점수는 서버가 `배점 × level ÷ 4`로 계산합니다(소수 둘째 자리). level은 0~4.
- `feedback`만 보내면 피드백만 바뀝니다(처음 저장은 level 필수, 아니면 400 LEVEL_REQUIRED). `{}`(둘 다 생략)는 해당 기준 점수 삭제입니다.
- 같은 값을 다시 보내도 결과가 같아 자동 저장 재시도에 안전합니다. 응답은 최신 rubric-scores 전체입니다.
- 미제출 409 SUBMISSION_REQUIRED, 없는 기준 404 CRITERION_NOT_FOUND, 범위 밖 400 INVALID_REQUEST.
- **확정 뒤 점수를 바꾸면 status가 DRAFT로 돌아갑니다.** 마지막 확정 총점은 `confirmedTotal`에 남고, 다시 confirm하면 갱신됩니다.
- 수준 또는 feedback이 실제로 바뀌면 이력(`/rubric-scores/history`)에 이전·새 수준과 previousFeedback/feedback, 당시 AI 추천 수준, 교사, 시각을 남깁니다. 과거 이력의 근거 필드는 null입니다.

`POST .../participants/{pid}/grade/confirm`

```json
{ "acceptAiForMissing": false }
```

- 모든 기준에 교사 값이 있어야 합니다. 없으면 409 GRADING_INCOMPLETE(메시지에 미입력 기준 이름).
- `acceptAiForMissing: true`면 근거 있는 추천만 명시적으로 수락합니다. 추천 불가(null)인 기여도·근거 부족·MANUAL 항목은 교사가 먼저 입력해야 하며, 그렇지 않으면409 GRADING_INCOMPLETE로 확정되지 않습니다. 본문은 생략할 수 있습니다.
- 응답의 `status: CONFIRMED`, `confirmedTotal`이 확정 총점입니다.

`PUT .../participants/{pid}/compliance-review`

```json
{ "decision": "HOLD", "note": "면담 후 판단" }
```

decision: `CONFIRMED_OK`(문제 없음 확인) / `HOLD`(판단 보류) / `CONFIRMED_VIOLATION`(위반 확인). 점수에는 영향을 주지 않습니다.

## 8. 계산 방식 (7-2, 7-4)

영역 점수 = 배점 × (수준 ÷ 4), 수준 = round(4 × s), s는 0~1 지표입니다. 총점은 영역 점수의 합입니다(예: 3·3·3·3·3 → 75점).

| 영역 | 지표 s |
|---|---|
| ① 목적 적절성 | (개념 이해·자료 탐색·아이디어 구체화 질문 수 − 답안 대리 작성 요청 수 × draftingPenalty) ÷ 전체 질문 수 |
| ② 자기 주도적 사고 | (첫 AI 질문 전 같은 문항에 자기 답안을 저장한 문항 비율 + 구체적인 추가 질문 비율 + AI 선택 이유 작성 여부) ÷ 3 |
| ③ 비판적 검토 | 완료된 AI 응답 중 "같은 문항의 다음 질문이 검증 요청" 또는 "그 응답을 고쳐 쓴 AI_REFERENCED 문단이 있음"의 비율 |
| ④ 최종 답안 기여 | 자동 추천하지 않음(null). 실제 저자 기여도는 유사도로 환산할 수 없으며 교사가 답안과 과정 근거를 확인해 직접 채점 |
| ⑤ 투명성 | (사용 AI 도구 기록 + 질문 기록 존재 + 답안 활용 부분 기록) ÷ 3 |

- 구체적인 추가 질문: 같은 문항의 두 번째 이후 질문 중 20자 이상이거나 "방금, 위에서, 그 부분" 등 앞 응답을 가리키는 질문(답안 대리 작성 요청 제외). 추가 질문이 없으면 전체 질문에 같은 기준을 적용합니다.
- 해당 AI 질문·완료 응답 등 근거가 없으면 관련 지표의 value/level과 추천 점수는 null입니다. 기록이 없다는 이유로 만점 또는0점을 주지 않습니다. ④는 항상 교사 확인이 필요합니다.
- **답안 출처 판정 V2** (`basis: TEXT_SIMILARITY_ESTIMATE_V2`, 모델 `LIT_RULE_BASED_EXPLAINED_V2_1`): 문단을 마침표·물음표·느낌표 기준 문장으로 나눠 문장마다 판정합니다. 평가 안내와 해당 문항 제목·내용에도 있는 표현은 비교에서 뺍니다.
  1. 정규화(소문자, 공백·문장부호 제거) 후 `min-span-length`(20)글자 이상 AI 답변과 그대로 이어지면 AI (`reason: SPAN_MATCH`)
  2. 3-gram 포함률이 `ai-referenced-threshold`(0.4) 이상이면 AI (`reason: GRAM_OVERLAP`)
  3. 그 외 DIRECT. AI 답변 복사 기록 가산점은 없습니다.
  - 분류는 DIRECT / AI_REFERENCED 두 가지뿐입니다. `contribution.direct + aiReferenced = 100`, `aiUsed`는 호환용으로 항상 0입니다. 비중은 문장 글자 수(문장 사이 공백 포함)로 합산합니다.
  - 문단 `source`는 AI 문장이 하나라도 있으면 AI_REFERENCED입니다. 문장별 결과는 `sentences[]`(`questionId, paragraphId, text, source, reason, longestSpan, similarity, conversationId, start, end`)로 내려갑니다. `start/end`와 `contribution.segments`는 `answerTexts[].text` 기준 UTF-16 위치입니다.
  - 단어를 모두 바꿔 쓴 문장은 글자 비교로 잡지 못합니다(개선안 2단계 LLM 판정 대상).
- **허용 범위 검사**:
  - Level 1(DISABLED): AI 대화가 있으면 VIOLATION, 200자 이상 붙여넣기·AI 답변 복사 기록은 CHECK.
  - AI 답변 사용(AI_REFERENCED) 문장 비중이 단계별 상한을 넘으면 모든 단계에서 CHECK입니다. 유사도만으로 VIOLATION을 확정하지 않습니다. 금지 정책과 내부 AI 호출 기록의 직접 불일치만 별도 확인 대상으로 표시합니다.
  - Level 1~3에서 답안 대리 작성 요청이 있으면 CHECK.
- 질문 분류는 **키워드 규칙**입니다. 규칙에 걸리지 않으면 OTHER(method `RULE_UNMATCHED`)로 두고, LLM 분류는 쓰지 않습니다. 비용·응답 지연·결과 재현성 때문에 교사 채점 데이터가 쌓인 뒤 도입 여부를 정하는 것을 권합니다.

### 설정값 (코드에 고정하지 않음)

`application.yml`의 `lit.grading.*` 또는 환경변수로 바꿉니다. 바꾼 뒤 `POST /analysis/run`으로 다시 계산하세요.

| 키 | 기본 | 의미 |
|---|---|---|
| ai-referenced-threshold | 0.4 | 문장 3-gram 포함률이 이 값 이상이면 AI |
| min-span-length | 20 | 정규화 후 이 글자 수 이상 그대로 일치하면 AI |
| drafting-penalty | 1.0 | ① 대리 작성 요청 감점 가중치 |
| appropriate-types | CONCEPT_UNDERSTANDING,RESEARCH,IDEA_EXPLORATION | ① 적절한 유형 |
| specific-question-min-length | 20 | ② 구체적 질문 최소 글자 수 |
| usage-high-share / usage-medium-share | 0.5 / 0.2 | 사용 방법별 정도 경계 |
| long-paste-length | 200 | Level 1 붙여넣기 확인 기준 |
| max-ai-used-share.{1..5} | 0, 0, 0.3, 0.7, 1.0 | 단계별 AI 답변 사용 비중 상한(V2부터 AI 비중 전체에 적용, 재보정 필요) |
| job-interval-ms | 3000 | 분석 작업 간격 |

## 9. 학생 화면이 남겨야 하는 데이터 (7-6)

| 데이터 | API | 상태 |
|---|---|---|
| 답안 버전 이력 | 기존 `PUT .../answer` 저장마다 `ANSWER_SAVED` 학습 이벤트(이전·새 내용, 버전, 출처)가 남습니다 | 추가 작업 없음 |
| 붙여넣기 | `POST /api/evaluations/{id}/learning-events` `{ "type": "PASTE", "occurredAt", "questionId", "length": 320 }` | 신규 |
| AI 답변 복사 | 같은 API `{ "type": "AI_ANSWER_COPY", "occurredAt", "questionId", "conversationId": 41 }` | 신규 |
| 사용한 AI 도구·활용 부분·선택 이유 | `GET·PUT /api/evaluations/{id}/ai-disclosure` (`/api/student/...` 별칭) | 신규 |
| 질문 유형 | 서버가 대화 조회·분석 시 붙입니다 | 프런트 작업 없음 |

현재 AI 사용 기록은 시스템 자동 생성입니다. 학생 GET은 읽기 전용이며, 기존 PUT은 입력을 저장하지 않는 호환 엔드포인트입니다. 제출/자동 종료 때 tools·usedParts·reason, status, conversationIds, evidence를 한 번 생성해 저장합니다. 자동 기록은 학생 자기 공개에 대한 점수 근거로 사용하지 않습니다. [전체 계약](AUTOMATIC_AI_DISCLOSURE.md)을 참고하세요.


## 통합 검증 후 보완 (2026-10-31)

- 모델 이름은 `LIT_RULE_BASED_RUBRIC_V1`입니다. 외부 Gemini/LLM 채점 호출이 아니라 저장된 기록에 대한 규칙 계산입니다.
- 질문·완료 응답 등 근거가 없으면 해당 추천은 null입니다. 입력이 없다는 이유로 만점/0점을 주지 않습니다.
- 학생 실제 기여도는 `studentContribution=null`을 유지합니다. contribution의 세 비율은 내부 응답과의 유사도 분류이며 저자·외부 AI 사용의 증거가 아닙니다. 이 영역 점수는 교사가 직접 입력해야 합니다.
- 부분 추천 합계에는 `recommendedMaxScore`와 `missingAiCriteria`를 함께 표시합니다. `/submissions`의 totalScore는 확정된 총점만 제공하고, 미확정이면 null입니다. totalAi는 참고 부분합입니다.
- 문장 유사도 초과는 CHECK이며 그 자체로 위반 확정/자동 감점하지 않습니다. 최종 규칙 준수 판단도 교사가 저장합니다.
- 채점 이력이 있으면 점수를 모두 삭제해도 기준 잠금이 풀리지 않습니다. 이력의 criterionId 의미를 보존합니다.
- 분석 작업은 평가 잠금과 requested_at 버전을 검사합니다. 이전 분석 요청의 결과·오류가 새로운 요청이나 완료 결과를 덮어쓰지 않습니다.
- 종료 후 저장 유예는 기본0입니다. 켠 경우에도 시간 만료는 실제 endsAt을 기준으로 유예를 계산하고, 채점·리포트가 생긴 뒤에는 저장을 허용하지 않습니다.

## 교사 근거 계약 보완

level만 전송하면 feedback을 유지합니다. feedback의 서버 제한은2000자, 초과400 INVALID_REQUEST. feedback=""는 근거 삭제입니다. 확정 오류 missingCriteria 및 보고서의 교사 근거 우선 적용은 [GRADING_FEEDBACK.md](GRADING_FEEDBACK.md)를 참고하세요.

## 구조화 근거·가입 동의 후속 계약

분석 reasons/정확한 quote, 정규화 답안 segments·PASTE 기록, 학생 상태/lastWarningAt, SSE와 비동기 ZIP은 [SIGNUP_CONSENT_ANALYSIS_EVIDENCE.md](SIGNUP_CONSENT_ANALYSIS_EVIDENCE.md)에 정의합니다. 기존 동기 ZIP·대화 ID·feedback 계약은 유지합니다.
