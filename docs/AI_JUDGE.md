# LIT AI 판정 (답안 기여도 · AI 사용 방법 · 루브릭 단계)

"LIT AI 판정 백엔드 전달 문서"(2026-11-10)를 구현한 내용입니다. 기존 알고리즘은 그대로 두고 AI가 판단하는 부분만 LLM으로 바꿨습니다.
AI 판정은 추천이며 최종 결정은 교사입니다. 애매한 것은 `needsReview: true`이고, 교사가 확정하면 그 값으로 덮고 보정 기록으로 남깁니다.

## 흐름과 모델

| 판정 | 시점 | 첫 판정 | 애매한 것 재판정 | 애매함 기준 |
|---|---|---|---|---|
| ① 답안 기여도 | 제출 직후(대기열) | Claude Haiku 1번(문장 묶음) | Gemini(analysis 키), Haiku 단계 2 문장만 | Haiku 단계 2 |
| ② AI 사용 방법 | 학생이 질문할 때(백그라운드) | Gemini(analysis 키) 3번 | Haiku 1번, 엇갈린 질문만 | 3번이 엇갈림 |
| ③ 루브릭 단계 | ①②가 끝난 뒤 | Gemini(rubric 키) 3번(항목마다) | Haiku 1번, 엇갈린 항목만 | 3번이 엇갈림(인용 검증 후) |

- 두 번째 모델에는 첫 모델의 답을 알려 주지 않습니다.
- 질문 용도·루브릭은 temperature를 지정하지 않습니다(모델 기본값). 0이면 3번이 항상 같아 애매한 것을 찾지 못합니다.
- 응답 형식이 틀리면 1번 다시 요청한 뒤 실패로 처리합니다.
- 같은 입력(같은 답안·질문·프롬프트 버전·모델)은 DB에 저장된 결과를 다시 씁니다.

## Gemini 키 3개와 한도

| 용도 | 설정 | 환경변수 |
|---|---|---|
| 학생 대화창 | `lit.ai.*` (기존) | `GEMINI_API_KEY` |
| 답안 기여도 재판정 + 질문 용도 | `lit.grading.judge.gemini.analysis-key` | `LIT_GRADING_JUDGE_GEMINI_ANALYSIS_KEY` |
| 루브릭 | `lit.grading.judge.gemini.rubric-key` | `LIT_GRADING_JUDGE_GEMINI_RUBRIC_KEY` |

- 한도는 키가 아니라 Google 프로젝트 단위라서, 키마다 **다른 프로젝트**에서 받아야 합니다.
- 키마다 분당(rpm)·하루(rpd) 호출 수를 따로 셉니다(`lit.grading.judge.limits.analysis|rubric|anthropic`). 분당 한도가 차면 기다렸다 보내고(최대 5분), 하루 한도가 차면 그 작업은 실패로 두고 나중에 다시 시도합니다.
- 429면 `Retry-After`만큼(없으면 5 → 10 → 20초) 기다렸다 **같은 키로만** 다시 보냅니다. 다른 용도의 키를 빌려 쓰지 않으므로 채점이 몰려도 학생 대화창이 막히지 않습니다.
- 기본 한도 값(15 rpm·1000 rpd)은 임시값입니다. 실제 프로젝트·요금제 한도에 맞춰 바꿔 주세요.
- Gemini 모델 기본값: `gemini-3.1-flash-lite`(세 판정 공통). 루브릭만 바꾸려면 `LIT_GRADING_JUDGE_GEMINI_RUBRIC_MODEL`.

## 채점 대기열과 공개 시점

- 학생별 `gradingStatus`: `QUEUED`(분석 전) / `GRADING`(답안 기여도·질문 용도·루브릭 진행 중, 실패 후 재시도 대기 포함) / `DONE` / `FAILED`(3번 다시 시도해도 실패).
- `GET /api/teacher/evaluations/{id}/grading-status` — 반 전체 진행(예: `done` 24 / `submitted` 30)과 학생별 상태. 평가 중에도 볼 수 있습니다.
- `POST /api/teacher/evaluations/{id}/participants/{pid}/grading/retry` — FAILED 학생 다시 채점.
- **평가가 끝나기 전(교사 종료·시간 만료 전)에는 결과를 내려 주지 않습니다.** `analysis`, `rubric-scores`, `usage-analysis`가 409 `GRADING_RESULTS_NOT_READY`를 돌려줍니다(`lit.grading.results-after-end`, 기본 true).
- 평가가 끝났는데 `DONE`이 아닌 학생은 응답의 `gradingStatus`로 "채점 중입니다"를 표시합니다.
- 다시 제출하면 마지막 제출만 채점합니다. 진행 중이던 이전 판정은 저장하지 않습니다.
- 실패해도 채점 화면은 열립니다(첫 모델 실패 → 기존 방식 결과, 두 번째 모델 실패 → `needsReview`).

## ① 답안 기여도 — 응답에 더한 것

`GET .../participants/{pid}/analysis`
- `sentences[]`: `source`(`DIRECT` | `AI`), `reason`, `needsReview`, `closestAiSentence`, `haikuLevel`, `geminiLevel`, `teacherSource`
- `reviewCount`: 확인이 필요한 문장 수
- `contribution`(`direct + aiReferenced = 100`, `aiUsed = 0`, `basis`)과 문단·구간의 `AI_REFERENCED` 이름은 그대로입니다.

교사 확정: `PATCH .../participants/{pid}/sentences/{sentenceId}/source` `{"source":"AI"}` 또는 `"DIRECT"` (기존 경로·`{"label":...}`도 받음)

## ② AI 사용 방법 — 응답

`GET .../students/{pid}/usage-analysis` 질문마다 `purpose`, `votes`(Gemini 3번), `haikuPurpose`(엇갈렸을 때만), `needsReview`, `teacherPurpose`, `method`(`LLM_GEMINI_X3` | `LLM_GEMINI_X3_HAIKU` | `TEACHER` | `KEYWORD_FALLBACK`).
`usage`(확정+임시, UNDECIDED 제외), `reviewCount`, `otherCount`, `undecidedCount`. Haiku가 실패하면 2번 이상 나온 Gemini 답, 세 답이 모두 다르면 `UNDECIDED`.

교사 확정: `PATCH .../students/{pid}/conversations/{conversationId}/purpose` `{"purpose":"REVIEW"}`

## ③ 루브릭 단계

- 학생 한 명 × 항목 5개(평가 기준의 indicator가 PURPOSE·SELF_DIRECTION·CRITICAL_REVIEW·CONTRIBUTION·TRANSPARENCY인 항목). 교사가 만든 MANUAL 항목은 판정하지 않습니다.
- 학생 기록: `Q`=문항, `U`=질문(LLM 용도 표시), `A`=AI 답변(1500자까지), `S`=대화 밖 활동(붙여넣기·복사·화면 이탈), `ANS`=최종 답안 문장(출처 판정 표시), `DIS`=활용 설명란.
- 프롬프트 순서: 지시·공통 규칙·답 형식(system) → 학생 기록 → 항목 본문. 같은 학생의 5개 항목 호출이 앞부분을 공유합니다(Gemini 암묵 캐시).
- 항목 본문: `src/main/resources/grading/rubric-judge-v1.md` (v3 루브릭 표 + 경계 판정, 문서의 문구 수정 3곳 반영). 루브릭 문서가 바뀌면 이 파일과 `RubricJudge.PROMPT_VERSION`을 함께 바꿉니다.
- 인용 검증: 각 답의 `evidence.quote`를 원문에서 공백·문장부호를 빼고 찾습니다. 없는 인용은 버리고, 근거가 하나도 남지 않은 단계 답은 그 회차를 무효로 봅니다.
- 확정: 3번 모두 같으면 확정. 엇갈리면 Haiku 1번 → Gemini 다수결과 같으면 확정, 다르거나 실패하면 `level: null`, `needsReview: true`, `reviewReason: "DISAGREEMENT"`.
- 특수 경우(모델 호출 없음): AI 대화 0개 → 자기 주도적 사고만 판정, 나머지 `NA`. 학생 질문 2개 이하 → `INSUFFICIENT_DATA`(교사 직접 입력).
- 점수: `배점 × (단계 − 1) ÷ 4`, 소수 첫째 자리 반올림(15점 4단계 = 11.3). 교사 점수도 같은 규칙입니다. **NA 항목은 총점과 만점에서 뺍니다**(`maxTotal`), 확정할 때도 요구하지 않습니다.
- `lit.grading.judge.rubric.stop-when-two-agree=true`로 "2번 같으면 멈춤"(아직 검증 전, 기본 false).

`GET .../participants/{pid}/rubric-scores` 항목마다 기존 필드(`ai`, `teacher`) + `notApplicable` + `judgment`:
```json
{"criterionId":3,"status":"OK","level":4,"score":15.0,"votes":[4,4,4],"haikuLevel":null,"needsReview":false,"reviewReason":null,
 "evidence":[{"src":"U","quote":"...","messageId":881,"sentenceId":null,"eventId":null,"range":[0,42]}],
 "met":"...","blocked":"...","teacherLevel":null,"method":"LLM_GEMINI_X3","runs":[...]}
```
응답 최상위: `gradingStatus`, `reviewCount`, `maxTotal`. `ai`는 LLM 판정이 확정됐을 때 그 값(0~4 척도)이고, 판정 전·실패 시에는 기존 규칙 기반 추천입니다.

교사 확정: `PATCH .../participants/{pid}/criteria/{criterionId}/level` `{"level":4}` (1~5단계, 기존 `PUT rubric-scores/{criterionId}`의 0~4와 같은 기록)

## 비용 확인

`GET /api/teacher/evaluations/{id}/judge-usage` — 학생·용도(SEMANTIC·PURPOSE·RUBRIC)·모델별 호출 수와 실제 토큰(입력·출력·생각). 배포 후 학생당 비용을 다시 계산할 때 씁니다.

## 저장

| 테이블 | 내용 |
|---|---|
| `semantic_judgments` / `sentence_labels` | ① 판정 캐시 / 교사 확정 |
| `purpose_classifications` / `purpose_labels` | ② 질문별 결과(`votes`, `haiku_purpose`) / 교사 확정 |
| `rubric_runs` | ③ 학생별 대기열(제출 버전, 상태, 시도 횟수) |
| `rubric_judgments` | ③ 항목별 판정 JSON(회차별 원래 답, 최종값, 프롬프트 버전, 모델) |
| `rubric_labels` | ③ 교사 확정(그때의 AI 판정 포함) |
| `judge_usage` | 호출별 토큰 |

## 배포 전 확인

- Gemini 키 2개(analysis·rubric)를 학생 대화용과 **다른 Google 프로젝트**에서 받아 `/opt/lit/app.env`에 넣습니다. 무료 프로젝트 여러 개를 쓰는 것이 약관에 맞는지 확인해 주세요.
- 실제 쓸 Gemini Flash-Lite로 질문 용도·루브릭 정확도를 아직 확인하지 않았습니다. 실제 제출물 30~50개를 교사 채점과 비교해 주세요.
- 학생 글이 외부 모델로 전송됩니다. 개인정보 처리방침·동의 화면에 판정 목적의 전송이 적혀 있는지 확인해 주세요. 모델에는 학생 이름 대신 내부 id만 보냅니다.
