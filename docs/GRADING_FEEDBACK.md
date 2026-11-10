# 루브릭 교사 채점 근거 및 보고서 반영

feature/grading-feedback 구현·미배포. 기반 브랜치는 feature/hwp-report-format(5ec6470)이며, 분리 PR이면 analysis-rubric-v2 → hwp-report-format → grading-feedback 순으로 머지합니다.

## 저장 계약 (rubric-scores)

`PUT /api/teacher/evaluations/{id}/participants/{pid}/rubric-scores/{criterionId}`

- `{level:3, feedback:"교사 근거"}`: 둘 다 저장, 응답 `criteria[].teacher.feedback`에 그대로 반환. 앞뒤 공백도 보존합니다.
- `{level:4}`: 기존 feedback 유지. 처음 저장하면 빈 문자열입니다. `feedback:null`도 level이 있는 경우 기존 근거를 유지합니다.
- `{feedback:""}`: 저장된 교사 근거를 빈 문자열로 지웁니다. level은 유지합니다. 첫 채점에 level 없이 feedback만 보내면400 LEVEL_REQUIRED입니다.
- `{feedback:"새 근거"}`: 단계 유지·근거만 수정합니다.
- `{}` 또는 `{level:null,feedback:null}`: 기존 계약대로 교사 점수를 삭제합니다. 근거만 지우려면 사용하지 마세요.
- 서버 feedback 제한은 **2000자(Java UTF-16 길이)**입니다. 초과 시400 INVALID_REQUEST (`code`, `message`). 프런트1000자 제한은 그대로 사용 가능합니다.
- 이 API의 level은 기존 **0~4**입니다. 별도 process-grading V2의 1~5단계와 혼용하지 않습니다.

## 변경 이력

`GET .../rubric-scores/history?afterId=0&limit=100`

기존 필드 유지, `previousFeedback`와 `feedback` 추가. 단계가 같더라도 근거가 실제로 바뀌면 이력을 남기며 확정 상태는 DRAFT로 돌아갑니다(confirmedTotal 보존). 완전히 같은 값의 반복 저장은 이력을 추가하지 않습니다.

```json
{
  "id":12,"criterionId":1,"previousLevel":3,"level":3,"aiLevel":4,
  "teacherId":"teacher-id","changedAt":"2026-11-07T14:00:00Z",
  "previousFeedback":"이전 근거","feedback":"새 근거"
}
```

과거 행은 새로운 두 필드가 null로 반환됩니다(과거 근거가 기록되지 않았음을 뜻하며 빈 문자열 삭제와 구분). 새 생성·AI 추천 수락 이력의 previousFeedback=null, feedback="" 가능. 점수 삭제 이력의 feedback=null. 스키마 ALTER ADD COLUMN IF NOT EXISTS로 기존 행을 보존하며 마이그레이션은 재실행 가능합니다.

## 확정

`POST .../grade/confirm`의 `{acceptAiForMissing:true}`:

- 교사 점수가 없는 기준에만 AI 추천 단계를 복사합니다. feedback="", adjusted=false. 기존 교사 단계·근거는 덮어쓰지 않습니다.
- AI도 추천하지 못한 기준이 남으면409 GRADING_INCOMPLETE. 기존 code/message 유지, 부족한 기준 목록을 **missingCriteria**로 추가합니다. 자동 채움과 이력 기록도 전체 롤백됩니다.

```json
{
  "code":"GRADING_INCOMPLETE",
  "message":"모든 기준을 채점해야 확정할 수 있습니다. 미입력: 교사 전용 기준",
  "missingCriteria":[{"criterionId":2,"name":"교사 전용 기준"}]
}
```

GET rubric-scores의 missingAiCriteria는 'AI 추천이 없는 기준명'이며 교사가 이미 직접 채점한 기준도 포함할 수 있습니다. 오류의 missingCriteria는 '이번 확정을 위해 아직 교사가 채점해야 하는 기준'만 포함합니다.

## 보고서

새 PDF/HWP 보고서 생성 시 실제 rubric-scores의 사용자 지정 기준·배점·교사 점수·feedback·저장된 AI 추천 사유를 `content.grading`에 고정합니다. 공통 ReportLayout은 이 스냅샷을 사용합니다.

평가 내용 우선순위는 **비어 있지 않은 교사 feedback → AI reason → 빈 셀**입니다. 점수도 교사 값 우선이며 AI만 있으면 '(AI 추천)' 표시합니다. 모든 기준에 교사 값이 있어야 총점을 표시하고 그 전에는 '미채점'입니다. AI 모델 호출 없이 저장된 grading_analyses 자료만 사용하므로 AI 추천 자료가 없으면 사유도 생성하지 않습니다.

기존 보고서 조회/version 지정은 그대로 보존합니다. 근거 변경 후 최신 보고서는 POST reports로 생성(교사 report.pdf는 version 생략 시 생성/재사용)해야 합니다. PDF·HWP 동일 version으로 조회하세요. 과거 저장본에 content.grading이 없으면 기존 TeacherReview 보고서로 출력합니다. process-grading V2 결과와는 별개입니다.

## 이전 확인 사항

- PDF/HWP 공통 서식 변경은 기반 브랜치에 포함돼 있습니다. docs/REPORT_FORMAT.md 참고.
- 학생 `POST /api/student/evaluations/{id}/events`와 `/api/evaluations/{id}/events`는 운영 배포된 별칭입니다(develop890c5a2, workflow42376198497 성공).
- 전체화면: FULLSCREEN_EXIT 또는 FULLSCREEN_LEAVE. 개발자 도구: DEVTOOLS_OPEN. 모두 경고 집계 및 /activity.warnings 표시 대상, 이벤트10종 합산60건/분.
- /activity.studentContribution=null 유지. 실제 작성 비율은 관측 불가하며 contribution.direct는 TEXT_SIMILARITY_ESTIMATE입니다. 별도 V2의 studentContribution은 25점 행동 평가이지 실제 작성 퍼센트가 아닙니다.
