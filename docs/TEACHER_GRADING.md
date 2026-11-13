# 교사 채점 화면 연동

이 문서는 fix/#16-teacher-grading 변경 기준입니다. PR #13, #15 위에 쌓인 후속 변경으로 병합·배포 전까지 운영 서버에는 적용되지 않습니다. 모든 경로는 `/api/teacher/evaluations/{id}/students`를 기준으로 하며 소유 교사의 `Authorization: Bearer {accessToken}`이 필요합니다. 다른 교사/다른 평가 참가자는 404, 인증 누락은 401입니다. 오류 응답은 기존 `{ code, message }`입니다.

## 1. 목록·검색·집계

`GET /api/teacher/evaluations/{id}/students?query=하&page=0&size=20`

- query는 닉네임 부분 일치 (대소문자 무시, 최대100자, 앞뒤 공백 제거). `%`, `_`는 와일드카드가 아닌 문자 그대로 검색합니다.
- 페이지는 0부터, size는1~100 (기본20). 참가자 수 자체의 새 하드 제한은 추가하지 않았으므로 페이지네이션을 사용하세요.
- 기존 items/page/size/totalElements/totalPages를 유지하고, 같은 배열의 별칭 students와 counts를 추가합니다.
- 상태 우선순위는 **WARNING > SUBMITTED > WRITING**. WINDOW_LEAVE/SHORTCUT_CAPTURE 신고가 1건 이상이면 WARNING입니다. 부정행위 확정·자동 감점 의미는 아닙니다.
- counts는 검색 조건에 맞는 전체 학생에 대해 위 표시 상태를 집계하며 현재 페이지 크기와 무관합니다. 세 집계는 중복되지 않고 합이 totalElements입니다. 경고 학생의 실제 제출 여부는 기존 submissionStatus·submittedAt으로 확인합니다.
- 기존 warning 필드는 연결 끊김/종료 후 미제출 등 주의 표시 의미를 유지합니다. 새 채점 화면의 경고 배지에는 status 또는 activityWarning을 사용하세요.

```json
{
  "counts":{"submitted":0,"warning":1,"writing":0},
  "students":[{"participantId":"학생 UUID","nickname":"이하랑","status":"WARNING","activityWarning":true,"submissionStatus":"SUBMITTED","submittedAt":"2026-10-31T01:00:00Z"}],
  "items":[{"participantId":"학생 UUID","nickname":"이하랑","status":"WARNING","activityWarning":true,"submissionStatus":"SUBMITTED","submittedAt":"2026-10-31T01:00:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1
}
```

실제 응답에서 items와 students는 동일합니다. 참가자 원래 상세 필드(presence/workStatus/reviewStatus 등)도 유지합니다.

## 2. 답안·문항

`GET .../students/{participantId}/answers`

```json
{
  "participantId":"학생 UUID","nickname":"이하랑","submittedAt":null,"status":"WRITING",
  "questions":[{"questionId":"문항 UUID","position":1,"title":"문항 1","content":"문항 내용","answer":{"questionId":"문항 UUID","content":"<table><tbody><tr><td>답안</td></tr></tbody></table>","version":3,"updatedAt":"2026-10-31T01:00:00Z"}}]
}
```

미제출은 최신 저장 답안, 제출 후에는 확정 snapshot을 반환합니다. 저장된 HTML·표를 다시 변환하지 않습니다. 프런트는 안전한 HTML 렌더링을 유지하세요.

`GET .../students/{participantId}/report.pdf`는 기존 활용 리포트 PDF 내보내기의 호환 경로입니다. 제출 후 사용할 수 있으며 답안·분류 요약·교사 리뷰를 포함합니다. 미제출은 409 SUBMISSION_REQUIRED로 버튼을 비활성화하세요.

- version 생략 시 현재 제출·검토 기준으로 리포트를 생성/재사용해 바로 내려줍니다. version≥1 지정 시 해당 저장 버전을 내려줍니다.
- 응답은 application/pdf, Content-Disposition 첨부 파일입니다. includeConversations=false가 기본이고 원문 포함은 true로 선택합니다.
- 기존 `/report/pdf` 경로는 유지합니다 (기존대로 이미 생성된 리포트 조회).
- PDF는 HTML 태그를 출력하지 않고 문단·줄바꿈·표의 각 행/셀 텍스트를 ` | ` 구분으로 변환합니다. 빈 셀은 `[빈 셀]`, 이미지는 `[이미지]`로 표시합니다. 브라우저 CSS·셀 병합·표 테두리를 그대로 재현하는 PDF는 아닙니다.

## 3. AI 대화 메시지

`GET .../students/{participantId}/conversations?questionId={선택}&page=0&size=200`

Turn 한 건을 USER/AI 메시지로 나눠 시간순으로 반환합니다. 메시지 id는 `턴ID-USER`, `턴ID-AI`로 안정적입니다. 기본200개, 최대200개/페이지이며 다음 페이지가 필요하면 page를 올립니다. 기존 데이터가 매우 많아도 전체를 무제한 반환하지 않습니다.

```json
[
  {"id":"1-USER","role":"USER","content":"힌트를 주세요","questionId":"문항 UUID","createdAt":"2026-10-31T01:00:00Z","status":"COMPLETED","errorCode":null},
  {"id":"1-AI","role":"AI","content":"**힌트**입니다.","questionId":"문항 UUID","createdAt":"2026-10-31T01:00:02Z","status":"COMPLETED","errorCode":null}
]
```

USER는 질문 수신 완료(COMPLETED), AI는 PENDING/COMPLETED/FAILED입니다. 대기·실패한 AI 메시지는 content=null이고 실패 시 errorCode가 포함됩니다. AI 메시지는 마크다운 원문입니다. 제출 후에는 고정된 대화 snapshot을 사용합니다. 다른 문항 필터는 404 QUESTION_NOT_FOUND입니다.

## 4. 활동·카드

기존 `GET /api/teacher/evaluations/{id}/participants/{participantId}/learning-events`는 WINDOW_LEAVE/SHORTCUT_CAPTURE를 포함한 기존 Event[] 형식을 유지합니다.

요약 카드는 `GET .../students/{participantId}/activity`를 사용합니다.

```json
{
  "studentContribution":null,
  "usage":[{"name":"이해·조사","percentage":42.9,"level":"MEDIUM","colorKey":"blue"},{"name":"기타","percentage":57.1,"level":"HIGH","colorKey":"neutral"}],
  "warnings":[{"eventId":5,"type":"WINDOW_LEAVE","questionId":null,"occurredAt":"2026-10-31T01:00:00Z","receivedAt":"2026-10-31T01:00:01Z","clientReported":true}],
  "warningsTotal":1,"hasMoreWarnings":false,
  "summary":"저장 1회, AI 질문 1건(완료 1건·실패 0건), 창 이탈·단축키 신고 1건입니다. 신고 기록만으로 부정행위를 판단하지 않습니다.",
  "analysisMethod":"KEYWORD_RULES_V1"
}
```

- studentContribution은 추정하지 않고 null입니다. DIRECT 신고값으로 실제 저자 기여도를 계산하지 않습니다.
- `usage`는 전체 질문 수(`usageAnalysis.questionCount`)를 분모로 계산합니다. 각 질문은 한 번씩 집계하며 기타와 판단 보류를 포함합니다. 질문이 있으면 비율 합은 100.0%이고, 질문이 없으면 빈 배열입니다.
- 기본 설정에서 `level`은 비중 50% 이상 `HIGH`, 20% 이상 50% 미만 `MEDIUM`, 20% 미만 `LOW`입니다. 기타와 판단 보류는 `colorKey: "neutral"`, 나머지는 `"blue"`입니다.
- warnings는 최근200개를 오래된 순으로 반환합니다. warningsTotal/hasMoreWarnings를 확인하고 전체 원문은 기존 learning-events 페이지 API를 사용하세요. occurredAt은 클라이언트 신고, receivedAt은 서버 수신 시각입니다.
- summary는 저장된 이벤트 건수의 문장 요약입니다. 외부 AI 사용·실제 캡처·부정행위를 추정하지 않습니다. 제출 후에는 확정 snapshot 기준입니다.

## 5. 루브릭 조회·개별 점수·감사 이력

`GET .../students/{participantId}/review`

```json
{
  "version":0,"analysisStatus":"DONE","analysisMethod":"KEYWORD_RULES_V1",
  "rubricStatus":"PROVISIONAL_FIVE_CRITERIA_0_TO_20",
  "criteria":[{"id":"PURPOSE_CLARITY","criterion":"활용 목적 명확성","score":null,"maxScore":20,"feedback":"교사 검토 전입니다.","evidence":[],"adjusted":false}],
  "analysis":[],"adjustments":[]
}
```

실제 criteria/analysis는 같은 구조의5개 영역을 반환합니다. analysis는 모달 호환을 위한 같은 교사 리뷰 데이터이며 AI가 산출한 별도의 점수 목록이 아닙니다. analysisStatus=DONE은 동기 키워드 분류가 수행됐다는 의미이며 AI 자동 채점 완료가 아닙니다. 점수가 없으면 null, 근거 없으면 빈 배열입니다.

영역 ID: PURPOSE_CLARITY, QUESTION_SPECIFICITY, VERIFICATION, REVISION, POLICY_COMPLIANCE. 영역과 배점은 임시 기준이며 최종 루브릭 확정이 아닙니다.

`PUT .../review/criteria/{criterionId}`

```json
{"score":18,"expectedVersion":0}
```

- score는 필수 키이며 정수0~20 또는 명시적 null(점수 제거)을 받습니다. 누락/범위 초과는400 INVALID_REQUEST, 잘못된 영역은404 CRITERION_NOT_FOUND입니다.
- expectedVersion은 선택입니다. 보내면 검토 버전 충돌 시409 REVIEW_VERSION_CONFLICT, 생략하면 행 잠금 후 마지막 요청을 적용합니다. 저장 응답은 최신 Rubric 전체입니다.
- 제출 후에만 수정할 수 있으며 미제출은409 SUBMISSION_REQUIRED입니다. 점수 외 기존 근거·의견·규칙 준수 검토는 유지합니다. 부분 채점과 null은 PDF에서도 미입력으로 표시합니다.
- 기존 PUT .../review 일괄 입력은 기존0~4점 계약을 유지합니다. 신규 화면의 GET은 기존 점수를 ×5로 환산하고 maxScore=20을 제공합니다. 새 개별 수정 후 TeacherReview는 maxScore=20, 구 일괄 저장 후는 maxScore=4입니다. 다른 상세·리포트 소비자도 TeacherReview.maxScore를 사용하고4점을 하드코딩하지 마세요. 기존 저장 JSON에 maxScore가 없으면4로 읽습니다.

누가·언제·이전 점수·새 점수·배점·리뷰 버전을 DB의 teacher_review_changes에 기록합니다. 새 개별 API와 기존 일괄 API 모두 적용합니다. 동등한3/4→15/20 환산만으로 조정 이력을 만들지 않으며, 실제 값 변경(점수 제거 포함)만 기록합니다. 기존 과거의 변경 이력을 소급 만들어내지는 않습니다.

Rubric.adjustments에는 최초200개 이력이 포함됩니다. 전체 페이지 조회는 `GET .../review/history?afterId=0&limit=100` (최대200)을 사용합니다. 마지막 id를 다음 afterId로 보냅니다.

```json
[{"id":1,"criterionId":"PURPOSE_CLARITY","previousScore":3,"score":18,"previousMaxScore":4,"maxScore":20,"teacherId":"교사 UUID","changedAt":"2026-10-31T01:00:00Z","reviewVersion":2}]
```

## 6. 종료

기존 POST /api/teacher/evaluations/{id}/end를 사용합니다. PR #15 변경이 포함된 이 브랜치에서는 시작한 평가의 미제출 답안을 서버가 자동 제출합니다. 시트의 ‘자동 제출 없음’ 설명은 이전 동작입니다. 자세한 제한 시간/미저장 입력 한계는 TIME_LIMITS.md를 참고하세요. 이 PR을 올렸다는 사실만으로 운영 동작이 바뀌지는 않습니다.
