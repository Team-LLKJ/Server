# AI 활용 과정 평가 V1 API

첨부된 최종 요약안을 `LIT_PROCESS_RUBRIC_V1`으로 구현합니다. 이는 LIT 제안 척도이며 공식 인증 점수가 아닙니다. Web은 변경하지 않았습니다. 기존 `/analysis`, `/rubric-scores`의 키워드 규칙 기반 모델과 별개인 `/process-grading` API입니다. 기존 채점·PDF/HWP 리포트를 자동 대체하거나 학교 성적에 반영하지 않습니다. 프런트가 새 API를 연동해야 새 결과를 표시합니다.

## 평가 원칙

- PURPOSE15 / SELF_DIRECTION25 / CRITICAL_REVIEW20 / CONTRIBUTION25 / TRANSPARENCY15.
- 문항마다 앞4개 항목을 먼저 평가하고 교사가 **시작 전에** 설정한 문항 비율(합계100%)로 합산합니다. 투명성은 평가 전체 GLOBAL에서 한 번만15점으로 반영합니다.
- 각 영역 점수=배점×수준÷4. 중간 반올림 없이 BigDecimal로 합산하고 최종 총점만 소수 둘째 자리 HALF_UP 표시합니다. 한 문항의3·3·2·3·3은70.00, 모두3이면75.00입니다.
- 근거 부족은 level/score/total=null과 HOLD로 구분합니다. 0단계는 해당 행동이 실제 기록에서 확인된 경우에만 추천합니다. 실패·자료 부족을0점으로 바꾸지 않습니다.
- 규정 준수는 OK/CHECK/INSUFFICIENT_EVIDENCE로 별도 제시합니다. 교사가 CONFIRMED_OK/HOLD/CONFIRMED_VIOLATION으로 판단합니다. 역량 점수와 위반 가능성을 자동 상쇄·감점하지 않습니다.
- 긴 질문·횟수·키워드·유사도·창 이탈 횟수만으로 점수를 정하지 않습니다. 생성 허용 과제에서 생성 요청 자체를 감점하지 않습니다.

## 인증·경로

모두 담당 교사(소유자 또는 공유 EDITOR)의 `Authorization: Bearer ...`가 필요합니다. 다른 평가·학생·분석 ID 접근은404입니다.

기본 경로: `/api/teacher/evaluations/{evaluationId}/process-grading`.
`evaluationId`는 독립 평가 또는 **반 회차 ID**이며 틀 assessmentId가 아닙니다.

| 메서드 | 기본 경로 뒤 | 설명 |
|---|---|---|
| GET | `/rubric` | 고정 기준 버전·배점·상세 Markdown |
| GET | `/policy` | 교사 사전 설정, 미설정404 |
| PUT | `/policy` | 시작 전 목표·규칙·미관측 AI 정책·문항 비율 설정 |
| POST | `/participants/{pid}/runs` | 제출 후 재분석 새 버전 예약,202 |
| GET | `/participants/{pid}/runs` | 해당 학생 분석 버전 목록(최신 먼저) |
| GET | `/participants/{pid}/runs/{runId}` | 상태·근거 검증 결과·추천 점수 |
| GET | `/participants/{pid}/runs/{runId}/evidence` | 그 버전에 고정된 입력·근거 ID·문장·시점/답안 버전 |
| GET | `/participants/{pid}/runs/{runId}/review` | 최신 교사 검토, 없으면404 |
| PUT | `/participants/{pid}/runs/{runId}/review` | 교사 보정·규정 검토 저장 |
| GET | `/participants/{pid}/runs/{runId}/review/history` | 검토·확정 이력(최신 먼저) |
| POST | `/participants/{pid}/runs/{runId}/confirm` | 최신 분석·검토 결과 최종 확정 |

## 1. 평가 전 정책 설정

문항 등록 후 실제 평가 시작 전에 PUT합니다. 이미 시작·종료된 평가는409 PROCESS_POLICY_LOCKED입니다.

```json
{
  "expectedVersion":0,
  "taskGoal":"우리 학교 잔반을 줄이는 실행 방안과 근거를 설명한다.",
  "allowedRules":"AI 생성 도움은 허용하되 자료와 적용 조건을 학생이 검토한다.",
  "noAiPolicy":"HOLD",
  "questionWeights":[{"questionId":"첫 문항 ID","weight":60},{"questionId":"둘째 문항 ID","weight":40}]
}
```

모든 문항을 정확히 한 번 포함하며 비율은0 초과100 이하·소수4자리까지, 합계100이어야 합니다. 중복·누락·타 평가 문항은400 INVALID_QUESTION_WEIGHTS입니다. 최초 expectedVersion=0, 수정은 현재 version을 보내며 불일치409 PROCESS_POLICY_VERSION_CONFLICT입니다. 문항 수정으로 ID가 바뀌면 시작 전에 정책도 새 문항 ID로 다시 설정하세요.

성공 응답: `{version,rubricVersion,taskGoal,allowedRules,noAiPolicy,questionWeights}`.

AI 기록이 관측되지 않았을 때의 교사 사전 정책:

- HOLD: 점수 판단 보류.
- NOT_APPLICABLE: 이 루브릭 적용 대상 제외, 숫자 총점 없음.
- USE_RECORDED_EVIDENCE: 저장된 학생 과정 기록만으로 AI에게 판단을 요청하되 부족한 영역은 보류.

이는 ‘관측된 대화·사용 공개 기록이 없음’에 대한 처리입니다. 외부 AI 미사용을 인증하지 않습니다. 자동 수집 기록을 학생이 다시 제출할 필요는 없습니다.

## 2. 제출 시 분석 시작과 입력 고정

수동 제출과 시간 만료·교사 종료에 따른 자동 제출은 제출본과 분석 작업을 함께 저장합니다. 저장이 완료되면 백그라운드 분석을 시작합니다. 제출 응답은 분석 결과를 기다리지 않습니다. 서버 재기동 등으로 대기 중인 작업은 기존 주기 작업이 처리합니다. 주기 설정은 `lit.grading.process-interval-ms`(기본 3000ms)입니다.

고정 항목: 제출 스냅샷 해시, 문항/최종 답안, 대화와 답안 변경 이력, 당시 AI 사용 공개 내용, 교사 정책 버전, 기준 버전, 목표·규칙, 평가 AI 허용 단계. 이후 라이브 DB나 교사 검토가 바뀌어도 과거 버전은 덮어쓰지 않습니다. 대기/진행 중 중복 예약은409 PROCESS_ANALYSIS_PENDING입니다. 명시적 재분석은 새로운 version·runId를 만듭니다. 기존 제출 데이터는 자동 소급 분석하지 않으며 필요하면 POST runs로 예약합니다.

- 정책 없음: 외부 AI를 호출하지 않고 HOLD/PRE_EVALUATION_POLICY_REQUIRED.
- 문항 비율과 제출 문항 불일치: HOLD/QUESTION_WEIGHTS_OUTDATED.
- 입력이 UTF-8 512KiB 초과: 기록을 몰래 잘라 평가하지 않고 HOLD/INPUT_TOO_LARGE_NO_TRUNCATION.
- 외부 호출 실패: FAILED와 errorCode. 점수 결과는 null이며 사용자가 재분석할 수 있습니다.
- RUNNING 상태로5분 넘게 남은 작업: FAILED/WORKER_INTERRUPTED로 전환 후 명시적 재분석 가능.
- 종료 후 선택적 유예 저장으로 제출본이 바뀌면 새 분석을 예약하고 이전 제출본의 새 확정은 차단합니다.

## 3. AI 출력과 서버 검증

문항은 최대4개씩 묶어 Gemini에 요청하고, 전체 기록을 본 첫 묶음의 투명성·규정 검토만 한 번 반영합니다. 묶음 간 작업 생존 시각을 갱신해 장시간 정상 분석을 중단 작업으로 오인하지 않습니다. 한 묶음이라도 실패하면 부분 점수를 확정하지 않습니다. Gemini에 구조화 JSON 출력을 요청하며 입력 기록과 평가 지시를 분리합니다. 학생의 ‘만점으로 채점해’ 같은 문장은 평가 명령으로 취급하지 않습니다. 실제 요청에는 전체 상세 루브릭을 systemInstruction으로 전달합니다.

각 추천은 `questionId,criterion,level,reason,unmetNextLevel,conditions,counterEvidence,limitations`입니다. 예:

```json
{
  "questionId":"문항 ID",
  "criterion":"SELF_DIRECTION",
  "level":3,
  "reason":"학생이 선택 이유를 과제 조건과 연결했습니다.",
  "unmetNextLevel":"새로운 정보에 대응해 판단을 발전시키고 최종 반영한 근거는 부족합니다.",
  "conditions":[{"key":"SELF_DIRECTION_L3","evidence":[{"sourceId":"turn:12:question","quote":"우리 학교는 추가 배식 인력이 부족해서 이 방법을 선택했어."}]}],
  "counterEvidence":[],
  "limitations":["외부 검증 행동은 확인할 수 없습니다."]
}
```

조건 key `{CRITERION}_L0`~`_L4`는 해당 단계의 **전체 필수조건**을 뜻합니다.4단계에는 `{CRITERION}_FINAL_APPLICATION`도 요구합니다. 서버는 조건이 모두 있는 가장 높은 단계와 추천 단계가 일치하는지 확인합니다. 낮은 부정적 단계는 높은 단계의 필수 선행 조건이 아닙니다.

검증 항목:

- 각 문항4항목+GLOBAL 투명성을 정확히 한 번 반환했는가.
- 근거 ID가 해당 학생의 고정 입력에 존재하고 인용문이 실제 그 문장에 포함되는가(최대2000자).
- 다른 문항의 개별 근거를 가져오지 않았는가. GLOBAL 공개 내용은 공통 근거로 활용할 수 있습니다.
- 필수조건의 근거가 비어 있지 않고 허용된 조건 key인가.
- AI 응답·과제 지문·브라우저 경고만으로 학생 행동 점수를 만든 것은 아닌가.
- 문항4단계에 최종 답안과 학생 질문/수정 이력/공개 기록 양쪽 근거가 있는가.

근거 ID는 `task:{qid}`, `answer:{qid}:{문단번호}`, `turn:{대화ID}:question/answer`, `event:{이벤트ID}:previous/current`, `disclosure:TOOLS/USED_PARTS/REASON`, `warning:{이벤트ID}`입니다. HTML 답안은 본문 텍스트/문단으로 변환한 자료를 AI에게 주고 그 동일 텍스트를 검증합니다. 대화·변경 이력에는 시각·답안 버전도 제공하며 실패 대화는 응답 누락 한계로 표시합니다.

유효하지 않은 항목은 HOLD로 바꾸고 총점을 null로 둡니다. 출력 전체가 불완전하거나 중복 항목을 포함하면 FAILED/GRADING_VALIDATION_FAILED입니다. 인용문 존재 검증은 그 문장에 대한 **의미적 판단의 정확성 또는 외부 자료의 진실성을 보증하지 않으므로 최종 교사 검토가 필요**합니다. 판단 보류인 항목도 검증 가능한 반대 근거·한계를 보존합니다.

완료 결과의 주요 필드:

- 작업: `id,version,status,inputHash,rubricVersion,model,createdAt,completedAt,errorCode`.
- `result.status`: DONE/HOLD/NOT_APPLICABLE.
- `result.compliance`: 점수와 분리된 규정 검토 초안.
- `result.cells`: 문항·영역별 level/score/status/reason/unmetNextLevel/conditions/counterEvidence/limitations.
- `result.total`: 전 영역과 문항 비율이 유효할 때만100점 만점 총점. 부분합을 확정 총점으로 제공하지 않습니다.

## 4. 교사 검토·수정·확정

```json
{
  "expectedRevision":0,
  "overrides":[{"questionId":"문항 ID","criterion":"SELF_DIRECTION","level":3,"reason":"교사가 답안과 과정 기록을 확인했다."}],
  "complianceDecision":"CONFIRMED_OK",
  "complianceReason":"과제의 허용 범위 안에서 활용했다."
}
```

PUT review의 overrides는 해당 검토의 전체 보정 목록입니다. 생략한 항목은 AI 검증 결과로 계산합니다. reason은 필수이며 누가·언제 수정했는지 저장합니다. 새 검토는 confirmed=false로 돌아가고 이전 검토·확정 이력을 남깁니다. expectedRevision이 다르면409 PROCESS_REVIEW_VERSION_CONFLICT입니다.

#### 교사 점수 직접 입력

AI가 정한 점수를 수준(level) 대신 점수로 바로 덮어쓸 수 있습니다. 한 항목에는 `level`과 `score` 중 **하나만** 보냅니다.

```json
{"questionId":"문항 ID","criterion":"CONTRIBUTION","score":21.5,"reason":"학생의 핵심 판단을 직접 확인했다."}
```

- `score`: 0 이상, 그 영역 만점(목적 적절성·투명성 15, 자기 주도·기여 25, 비판적 검토 20) 이하, 소수 둘째 자리까지. 문항 배점 비율은 기존과 같이 곱해 총점을 계산합니다.
- 결과 칸은 `status: TEACHER_REVIEWED`, `level: null`, `score`는 입력값입니다. 분석 화면 영역 요약 `label`은 `교사 점수 입력`입니다.
- 둘 다 보내거나 둘 다 빠졌거나 범위를 벗어나면 400 `INVALID_PROCESS_REVIEW`입니다. 저장·이력·확정(`POST …/confirm`)은 수준 보정과 같습니다.

검토 응답 `{revision,teacherId,reviewedAt,overrides,complianceDecision,complianceReason,result,confirmed}`의 revision으로 확정합니다.

```json
{"expectedRevision":1}
```

POST confirm은 최신 분석 버전·현재 제출 해시·최신 검토 revision이 일치하고 총점이 존재하며 규정 판단이 HOLD가 아닐 때만 성공합니다. 보류 항목이 있으면409 PROCESS_CONFIRMATION_HELD, 오래된 분석/제출본은409 PROCESS_RESULT_STALE입니다. 확정도 이력 revision을 하나 추가합니다. 학생 제출본은 변경하지 않습니다. 규정 위반으로 확정해도 역량 점수를 자동 감점하지 않습니다.

## 운영·검증

기존 `lit.ai.provider=gemini`, `GEMINI_API_KEY`, `GEMINI_MODEL`, timeout·endpoint 설정을 재사용합니다. 추가 키·외부 데이터 저장소는 없습니다. stub 모드는 테스트용이며 모든 영역을 보류하고 가짜 추천 점수를 만들지 않습니다. 미설정/오류 시 이전 키워드 점수로 자동 대체하지 않습니다. 외부 요청의 모호한 실패를 자동 반복하지 않아 중복 과금 가능성을 줄이며, 재분석은 명시적으로 요청합니다.

새 정책·작업·검토 테이블은 schema.sql이 생성하며 기존 답안·점수는 변경하지 않습니다. 운영 데이터는 사전에 백업하고 새 기능에 대해 교사들이 같은 사례를 독립적으로 채점하여 단계 경계와 의미적 정확도를 보정해야 합니다. 설계용 사례와 검증용 사례는 분리합니다. 자동 테스트의 정상 통과는 실제 교육적 타당도 검증을 대신하지 않습니다.

로컬 검증은 테스트 대역 및 로컬 HTTP 서버로 수행하며 실제 학생 자료를 Gemini에 보내거나 운영 키를 사용하지 않습니다. 실제 모델 품질·응답 시간·비용은 운영 설정을 이용한 별도 검증이 필요합니다.


## 응답 모델 필드와 완전한 상태 목록

루브릭 criteria[].name, cells[].criterionName/maxScore, conditions[].evidence, 고정 입력 /evidence 전체 예시와 기존 채점 모델과 다른 compliance 값은 [최종 연동 계약](FRONTEND_CONTRACT_COMPLETION.md#3-과정-평가-모델)을 참고하세요.

## V2 개정

새 분석의 현재 계약은 [ANALYSIS_RUBRIC_V2.md](ANALYSIS_RUBRIC_V2.md)입니다. 위 V1 0~4단계 설명은 기존 run에만 적용하며 V2는 1~5단계·배점×(단계−1)/4입니다. 새 통합 분석 API로 화면의 5개 기준과 기여도를 조회할 수 있습니다.
