# AI 활용 분석·학생 기여도 V2

`feature/analysis-rubric-v2` 구현. 배포 전 변경 사항입니다. 첨부 `message (9).txt`의 행동 기준을 적용합니다. 화면 시안의 모든 항목 20점 표시는 사용하지 않습니다.

## 루브릭·단계

`GET /api/teacher/evaluations/{id}/process-grading/rubric`은 `LIT_PROCESS_RUBRIC_V2`, `levels`(1~5 및 표시명), `scoreFormula`, 기준별 이름·배점과 전체 `criteriaMarkdown`을 반환합니다.

| criterion | 이름 | 배점 |
|---|---|---:|
| PURPOSE | AI 활용 목적의 적절성 | 15 |
| SELF_DIRECTION | 자기 주도적 사고 | 25 |
| CRITICAL_REVIEW | AI 답변의 비판적 검토 | 20 |
| CONTRIBUTION | 최종 답안에 대한 학생의 기여 | 25 |
| TRANSPARENCY | AI 활용 과정의 투명성 | 15 |

단계 1/2/3/4/5의 표시는 매우 못함/못함/보통/잘함/매우 잘함입니다. 항목 점수는 `maxScore * (level - 1) / 4`. 25점 항목 4단계는 18.75점입니다. 보류는 `level=null, score=null, status=HOLD`, 총점도 null이며 0점으로 취급하지 않습니다.

Gemini는 질문·후속 행동·답안 반영을 근거로 단계를 추천합니다. 서버는 문항·조건별 원문 인용과 근거 ID를 검증하고 점수를 계산합니다. 문장 모방·전문적 표현·횟수·유사도·AI 생성문 사용·브라우저 경고만으로 수준을 결정하지 않습니다. 1단계 역시 관측된 부정적 행동 근거가 필요합니다. 최고 단계는 최종 답안과 과정 근거를 함께 연결합니다. 인용 검증이 AI의 의미 판단 정확성을 보장하지 않으며 교사가 최종 확인합니다.

## 분석 화면 통합 조회

`GET /api/teacher/evaluations/{id}/process-grading/participants/{pid}/analysis`

교사 Access Token 필요. 소유·공유 권한은 기존 과정 평가 API와 동일합니다. 200 응답:

```json
{
  "participantId": "participant-id",
  "rubricVersion": "LIT_PROCESS_RUBRIC_V2",
  "runId": "run-id",
  "status": "DONE",
  "confirmed": false,
  "total": 75.00,
  "compliance": {"status":"CHECK","reason":"교사 확인","evidence":[],"limitations":[]},
  "criteria": [],
  "studentContribution": {
    "criterion":"CONTRIBUTION",
    "name":"최종 답안에 대한 학생의 기여",
    "maxScore":25,
    "level":4,
    "label":"잘함",
    "score":18.75,
    "status":"DONE",
    "cells":[]
  },
  "aiUsage": [],
  "textSimilarity": {"direct":60,"aiReferenced":25,"aiUsed":15,"basis":"TEXT_SIMILARITY_ESTIMATE"},
  "limitations":["문장 유사도 비중은 실제 작성자 비율이나 역량 점수가 아닙니다."]
}
```

위 예시의 `criteria`, `cells`, `aiUsage`는 구조 설명을 위해 비워 둔 축약 예시입니다. 실제 완료 응답 criteria는 5항목이며 각 cells에는 문항별 단계·점수·이유·conditions[].evidence[]·counterEvidence·limitations가 있습니다. aiUsage는 기존 질문 유형별 count/share 통계입니다.

- 최신 run만 표시합니다. 새 run이 대기·실패 중이면 과거 완료 점수를 대신 노출하지 않습니다. run 없음은 NOT_ANALYZED, 대기/진행/실패는 PENDING/RUNNING/FAILED이며 total과 studentContribution은 null입니다.
- 교사 review가 있으면 해당 최신 review 결과를 표시하고 확정 여부는 confirmed에 표시합니다. 상세 run/evidence/review/history/confirm 경로는 기존과 같습니다. 재분석은 기존 `POST .../participants/{pid}/runs`(202)입니다.
- 다문항 PURPOSE/SELF_DIRECTION/CRITICAL_REVIEW/CONTRIBUTION 점수는 사전 questionWeights로 합산하고 TRANSPARENCY는 평가 전체 한 번만 계산합니다. 서로 다른 문항 단계는 평균 내지 않고 summary.level=null, label='문항별 단계 상이'로 표시하며 cells로 개별 단계를 확인합니다. 미완료 항목은 HOLD입니다.
- studentContribution은 25점 역량 평가이며 작성량 퍼센트가 아닙니다. textSimilarity는 기존 분석의 문장 유사도 추정 비중으로 별개입니다. 기존 `analysis`·`rubric/scores`의 통계 기반 추천은 V2 근거 평가 결과가 아닙니다. 새 화면은 이 API의 criteria/studentContribution을 사용해야 합니다.
- 규정 준수는 OK/CHECK/INSUFFICIENT_EVIDENCE 별도 판단이며 위반 확정·자동 감점 없음. 교사 사전 정책 필수와 AI 미사용 HOLD/NOT_APPLICABLE/USE_RECORDED_EVIDENCE는 유지됩니다.
- 오류: 401 인증 오류, 404 EVALUATION_NOT_FOUND/PARTICIPANT_NOT_FOUND. 리포트/HWP에 V2 결과를 자동 병합하지 않습니다.

## 과거 결과 호환

기존 V1 정책·입력·분석·교사 검토를 덮어쓰거나 단계에 일괄 +1하지 않습니다. V1 run은 0~4단계와 기존 계산을 유지하고 V1 검토도 동일합니다. 새 제출·재분석 run은 기존 사전 정책의 허용 범위·문항 가중치를 보존한 V2 입력 스냅샷으로 생성합니다. 새 정책은 V2로 저장합니다. 새 점수 변경 검증은 해당 run 기준으로 V1 0~4 / V2 1~5이며 틀린 범위는 400입니다. V1 job이 배포 당시 대기 중이어도 V1 루브릭·AI 스키마로 처리합니다.
