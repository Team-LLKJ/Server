# 교사 검토·AI 활용 리포트 API

브랜치: feature/#4-teacher-reports. 기존 가입·PIN 참여·문항·제출 API와 함께 사용합니다.

교사 API는 `Authorization: Bearer {accessToken}`, 학생 API는 `X-Participant-Token: {participantToken}`이 필요합니다. 교사는 본인이 만든 평가만, 학생은 본인 제출 리포트만 접근합니다. 닉네임은 식별 키가 아니며 participantId를 사용합니다.

## API

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | /api/teacher/evaluations/{id}/students?page=0&size=20 | 이탈·연결 끊김 포함 전체 학생 목록 |
| GET | /api/teacher/evaluations/{id}/students/{participantId} | 저장 답안·AI 대화·학습 이력·활용 분류·교사 검토 |
| PUT | /api/teacher/evaluations/{id}/students/{participantId}/review | 5개 영역 교사 점수·규칙 준수 검토 저장 |
| POST | /api/teacher/evaluations/{id}/students/{participantId}/reports | 제출 스냅샷 리포트 생성 또는 동일 결과 반환 |
| GET | /api/teacher/evaluations/{id}/students/{participantId}/reports | 저장된 리포트 버전 목록 |
| GET | /api/teacher/evaluations/{id}/students/{participantId}/report?version=1 | 특정 버전, 생략하면 최신 버전 |
| GET | /api/teacher/evaluations/{id}/students/{participantId}/report/pdf?version=1&includeConversations=false | PDF 첨부 다운로드 |
| POST | /api/evaluations/{id}/reports | 학생 자신의 제출 리포트 생성 |
| GET | /api/evaluations/{id}/report?version=1 | 학생 자신의 리포트 조회 |
| GET | /api/evaluations/{id}/report/pdf?version=1&includeConversations=false | 학생 자신의 PDF 다운로드 |
| POST | /api/teacher/evaluations/{id}/end | 기존 종료 API 보강: 진행 AI 실패 확정·추가 작성 차단 |

## 학생 목록·상세

목록은 `items, page, size, totalElements, totalPages`로 반환합니다. page는0부터, size1~100(기본20), 닉네임·ID 오름차순입니다. 빈 목록은 items=[].

Student 필드: participantId, nickname, presence, submissionStatus, workStatus, warning, submittedAt, reportVersion, reviewStatus.

- presence: CONNECTED / DISCONNECTED(30초 이상 무응답) / LEFT(이탈).
- submissionStatus: NOT_SUBMITTED / SUBMITTED / CLOSED_UNSUBMITTED.
- workStatus: WAITING / IN_PROGRESS / SUBMITTED / ENDED / EXPIRED.
- warning: 미제출 상태이고 연결이 끊겼거나 평가가 닫힌 경우 true. 부정행위 경고가 아닙니다.
- reviewStatus: NOT_REVIEWED / COMPLIANT / NEEDS_REVIEW. 교사 검토 상태입니다.
- reportVersion: 최신 저장 버전 또는 null. 교사 검토 변경 직후에는 POST reports로 최신 버전을 생성하세요.

상세는 `{ student, work, analysis, teacherReview }`. 미제출은 현재 저장 데이터, 제출 후에는 고정된 submission.snapshot을 반환합니다. teacherReview 미등록은 null입니다. 폴링할 때 프런트는 선택된 participantId를 유지해야 합니다.

## 분류와 점수

추가 AI 호출 없이 질문 키워드 기반 KEYWORD_RULES_V1으로 복수 목적을 분류합니다. 목적은 IDEATION, RESEARCH, STRUCTURING, VERIFICATION, REVISION, ANSWER_DRAFTING, CONCEPT_HELP, UNCLASSIFIED. 단계는 EXPLORATION, PLANNING, REVIEW, REVISION, DRAFTING, UNCLASSIFIED. evidence에는 실제 질문 원문을 넣고 confidence=RULE_MATCH_REVIEW_REQUIRED로 교사 검토 필요를 표시합니다. 의미 분석·정답 판정이나 외부 AI 사용 자동 탐지는 아닙니다.

5개 영역은 아직 확정되지 않아 아래 임시 기준으로 표시합니다. 각0~4점, 총20점이며 교사가 입력합니다. 미입력은0점으로 바꾸지 않습니다. 자동 점수·자동 부정행위 판정은 없습니다.

```json
{
  "expectedVersion": 0,
  "compliance": "COMPLIANT",
  "ratings": [
    { "criterion": "PURPOSE_CLARITY", "score": 3, "evidence": "활용 목적을 질문에 명시함" },
    { "criterion": "QUESTION_SPECIFICITY", "score": 3, "evidence": "조건과 초안을 구체적으로 제시함" },
    { "criterion": "VERIFICATION", "score": 2, "evidence": "검증 과정에 관한 교사 확인 근거" },
    { "criterion": "REVISION", "score": 3, "evidence": "답안 수정 이력 확인" },
    { "criterion": "POLICY_COMPLIANCE", "score": 4, "evidence": "교사 허용 규칙과 대조함" }
  ],
  "comment": "교사 검토 의견"
}
```

영역은 각각 한 번씩5개 필수. score는 생략·null 불가, evidence 공백 불가·최대2000자, comment 필수·최대5000자(빈 문자열 가능). compliance=NOT_REVIEWED / COMPLIANT / NEEDS_REVIEW. expectedVersion은 최초0, 이후 직전 TeacherReview.version을 전달합니다. 충돌은409 REVIEW_VERSION_CONFLICT입니다.

## 리포트와 PDF

제출 후30초 주기의 백그라운드 작업에서 리포트를 생성하고, 교사 검토 변경 후 새 버전도 생성합니다. POST로 즉시 생성할 수 있습니다. 제출 스냅샷·분류·교사 검토가 동일하면 동일 id/version을 반환합니다. 검토가 달라지면 version을 증가시키고 이전 버전은 유지합니다. 생성 시점에 기록이 부족하면 missingData와 limitations를 표시하며 점수를 임의로 채우지 않습니다.

UsageReport: id, version, createdAt, content. content에는 evaluationId, title, participantId, nickname, submittedAt, submissionHash, submission, analysis, teacherReview, rubricStatus, limitations가 있습니다. submission은 확정된 원문 스냅샷입니다. AI 도구·모델, 작성·참고·수정 기록은 submission.conversations / learningEvents에 포함됩니다.

PDF는 application/pdf, Content-Disposition attachment입니다. 한글 글꼴을 포함하고 긴 텍스트를 줄바꿈·페이지 분할합니다. 지원하지 않는 문자는 ?로 대체합니다. includeConversations=false(기본)이면 질문·응답 원문과 분류 근거 원문을 PDF에서 제외합니다. true일 때만 대화 원문을 추가합니다. 최종 답안과 교사 입력 근거는 항상 포함하므로 교사가 원문을 인용해 작성한 내용까지 제거하는 기능은 아닙니다. 버전·생성/제출 시각·미작성 표시·임시 기준·판단 한계를 포함합니다.

## 평가 종료

종료는 반복 가능하며 학생 답안·기존 제출 기록을 보존합니다. PENDING AI는 즉시 FAILED / EVALUATION_CLOSED로 기록하며 늦게 도착한 답변으로 덮어쓰지 않습니다. 미제출을 자동 제출하지 않습니다. 종료 후 학생 state는 ENDED(기제출 학생은 SUBMITTED), 미제출 학생은 교사 목록에 CLOSED_UNSUBMITTED로 남습니다. 신규 참가·작성·제출·새 AI 질문은 차단됩니다.

## 오류

- 400 INVALID_REQUEST: 페이지·버전·본문 검증 실패.
- 400 INVALID_RUBRIC:5개 영역 누락·중복.
- 401: 교사 인증 또는 참가자 토큰 누락·무효.
- 404 EVALUATION_NOT_FOUND / PARTICIPANT_NOT_FOUND: 평가·참가자 없음 또는 타인 소유.
- 404 REPORT_NOT_FOUND: 미생성·존재하지 않는 리포트 버전.
- 409 SUBMISSION_REQUIRED: 미제출 리포트 생성·교사 점수 저장 시도.
- 409 REVIEW_VERSION_CONFLICT: 다른 검토가 먼저 저장됨. 상세 재조회 필요.

교사·학생의 PDF 다운로드에도 같은 인증 헤더를 사용합니다. 브라우저에서는 fetch 후 Blob으로 저장하고 URL에 토큰을 넣지 마세요.
