# LIT AI 판정 (답안 기여도 · AI 사용 방법 · 루브릭 단계)

"LIT AI 판정 백엔드 전달 문서"(2026-11-10)를 구현한 내용입니다. 기존 알고리즘은 그대로 두고 AI가 판단하는 부분만 LLM으로 바꿨습니다.
AI 판정은 추천이며 최종 결정은 교사입니다. 애매한 것은 `needsReview: true`이고, 교사가 확정하면 그 값으로 덮고 보정 기록으로 남깁니다.

## 흐름과 모델

| 판정 | 시점 | 첫 판정 | 애매한 것 재판정 | 애매함 기준 |
|---|---|---|---|---|
| ① 답안 기여도 | 제출 직후(대기열) | Claude Haiku 1번(문장 묶음) | Gemini(analysis 키), Haiku 단계 2 문장만 | Haiku 단계 2 |
| ② AI 사용 방법 | 학생이 질문할 때(백그라운드) | Gemini(analysis 키) 3번 | Haiku 1번, 엇갈린 질문만 | 3번이 엇갈림 |
| ③ 루브릭 단계 | ①②가 끝난 뒤(과정 채점 작업) | Gemini(rubric 키) 3번(항목마다) | Haiku 1번, 엇갈린 항목만 | 3번이 엇갈림(인용 검증 후) |

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

- 학생별 `gradingStatus`: `QUEUED`(분석 전) / `GRADING`(답안 기여도·질문 용도·루브릭 진행 중) / `DONE` / `FAILED`(마지막 과정 채점 작업 실패 등).
- `GET /api/teacher/evaluations/{id}/grading-status` — 반 전체 진행(예: `done` 24 / `submitted` 30)과 학생별 상태. 평가 중에도 볼 수 있습니다.
- `POST /api/teacher/evaluations/{id}/participants/{pid}/grading/retry` — FAILED 학생 다시 채점(실패한 과정 채점이면 새 작업).
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

## ③ 루브릭 단계 — 과정 채점(process-grading)에 붙음

rubric 키가 설정되면 과정 채점 작업(`process_grading_jobs`)이 기존 문항별 Gemini 추천 대신 이 판정으로 결과를 만듭니다. 화면·API는 기존 과정 채점 그대로입니다.

- **학생 단위**: 항목마다 한 칸, `Cell.questionId = null`(문항 가중치 없음). 작업 `model`은 `LLM_RUBRIC_GEMINI_X3_HAIKU`.
- **시점**: 제출 때 작업이 만들어지고, 질문 용도·답안 기여도 분류가 끝난 학생부터 처리합니다(최대 10분 기다린 뒤에는 있는 결과로 진행).
- **학생 기록**: 제출 시점에 고정된 `Input.sources`로 만듭니다. `Q`=문항, `U`=질문(질문 용도 표시), `A`=AI 답변(1500자까지), `S`=브라우저 경고(붙여넣기 등), `ANS`=최종 답안 문단, `DIS`=활용 설명란.
- **프롬프트 순서**: 지시·공통 규칙·답 형식(system) → 학생 기록 → 항목 본문(`grading/rubric-judge-v1.md`, v3 표 + 경계 판정 + 문서의 문구 수정 3곳). 바꾸면 `RubricJudge.PROMPT_VERSION`도 올립니다.
- **인용 검증**: 각 답의 인용을 원문에서 공백·문장부호를 빼고 찾아 `Citation`(sourceId·start·end·location)으로 연결합니다. 원문에 없는 인용은 버리고, 근거가 하나도 남지 않은 단계 답은 그 회차를 무효로 봅니다.
  `…`·`...`로 중간을 줄인 인용은 조각마다 같은 줄에서 순서대로 찾습니다. 회차마다 `runs[].rawLevel`에 모델이 답한 단계가 그대로 남습니다.
- **확정**: 3번 모두 같으면 `RECOMMENDED`. 엇갈리면 Haiku 1번 → Gemini 다수결과 같으면 `RECOMMENDED`, 아니면 `HOLD`(level·score null, `judge.needsReview=true`, `judge.reviewReason="DISAGREEMENT"`).
  Haiku 답의 인용을 원문에서 못 찾으면 1번 더 묻습니다. 그래도 Haiku 판정이 없으면 `judge.haikuError`에 이유가 남습니다(`NOT_CONFIGURED`: Anthropic 키 없음, `NO_VERIFIED_EVIDENCE`, 호출 오류 코드). 이런 결과는 캐시하지 않아 다음 분석 때 다시 묻습니다.
- **특수 경우(모델 호출 없음)**: AI 질문 0개 → 자기 주도적 사고만 판정, 나머지 `NOT_APPLICABLE`. 학생 질문 2개 이하 → `HOLD`(`INSUFFICIENT_DATA`). 사전 정책의 제외 항목은 `NOT_APPLICABLE`. 사전 정책이 없어도 판정하며, AI 미사용 정책이 `NOT_APPLICABLE`이면 전 항목 NA.
- **점수(모두 정수, 소수 첫째 자리에서 반올림)**
  - 재분배 배점(`Cell.adjustedMaxScore`): NA 칸을 뺀 원래 배점 합을 100점이 되게 — `원래 배점 × 100 ÷ 판정 대상 배점 합` → 정수. NA가 없으면 원래 배점, NA 칸은 0.
  - 칸 점수(`Cell.score`): `재분배 배점 × (단계 − 1) ÷ 4` → 정수(15점 4단계 = 11, 25점 4단계 = 19).
  - 교사 직접 점수: 원래 배점 기준 정수로 반올림한 뒤(13.33 → 13) 재분배 배점 비율로 환산 → 정수(자기 주도 하나만 남으면 13 × 100/25 = 52).
  - 총점 = 반올림한 칸 점수의 합(재분배 배점의 반올림 때문에 만점 합이 99·101이 될 수 있습니다).
  - **HOLD 칸은 재분배하지 않습니다**: 자기 재분배 배점을 `heldMaxScore`로 남기고 점수·총점은 교사가 채울 때까지 null.
  - `partialScore`: `assessedScore`(판정된 칸 점수 합), `assessedMaxScore`(그 칸 배점 합), `heldMaxScore`(HOLD 칸 배점 합), `notApplicableMaxScore`(NA 칸의 **원래** 배점 합).
- 칸마다 `judge`: `votes`(Gemini 회차별), `haikuLevel`, `needsReview`, `reviewReason`, `method`, `runs`(회차별 원래 답·근거), `promptVersion`, 모델 이름.
- 같은 기록·프롬프트·모델이면 `rubric_judgments`에 저장된 판정을 다시 씁니다(재분석해도 다시 부르지 않음).

학생 단위 판정 통합 테스트에서 캡처한 실제 API 직렬화 예시(모델 응답은 테스트 대역): [examples/process-rubric-run.json](examples/process-rubric-run.json)

교사 검토·확정(기존 API):
- `PUT .../process-grading/participants/{pid}/runs/{jobId}/review` — `overrides[].questionId`는 **null**, `criterion`, `level`(1~5) 또는 `score`, 선택 입력 `reason`. 교사가 채운 칸은 `TEACHER_REVIEWED`이고 `judge.needsReview`가 풀립니다.
- `POST .../runs/{jobId}/confirm` — HOLD 칸이 남아 있으면 409 `PROCESS_CONFIRMATION_HELD`.
- **마지막 제출본만**: 다시 제출하면(종료 후 유예 시간의 마지막 저장으로 자동 제출본이 갱신되면) 시작 전인 이전 작업은 `SUPERSEDED`가 되고, 이전 작업의 검토·확정은 409 `PROCESS_RESULT_STALE`입니다.

학생 PDF·HWP: 학생 단위 결과는 **교사가 확정한 결과만** 씁니다. 확정 전에는 점수·AI 추천 없이 "채점 중", 확정 후 항목 점수(재분배 배점 기준 정수)와 총점, NA 항목은 "해당 없음"으로 표시합니다.

`rubric-scores` API(규칙 기반 추천)는 LLM 판정을 쓰지 않습니다. 반올림만 같은 규칙(소수 첫째 자리)입니다.

## 비용 확인

`GET /api/teacher/evaluations/{id}/judge-usage` — 학생·용도(SEMANTIC·PURPOSE·RUBRIC)·모델별 호출 수와 실제 토큰(입력·출력·생각). 배포 후 학생당 비용을 다시 계산할 때 씁니다.

## 저장

| 테이블 | 내용 |
|---|---|
| `semantic_judgments` / `sentence_labels` | ① 판정 캐시 / 교사 확정 |
| `purpose_classifications` / `purpose_labels` | ② 질문별 결과(`votes`, `haiku_purpose`) / 교사 확정 |
| `process_grading_jobs` / `process_grading_reviews` | ③ 작업별 결과(학생 단위 칸) / 교사 검토·확정 기록(보정용) |
| `rubric_judgments` | ③ 항목별 판정 캐시(회차별 원래 답, 최종값, 프롬프트 버전, 모델) |
| `rubric_runs`, `rubric_labels` | 사용하지 않음(#62의 이전 구조, 기존 데이터 삭제용으로만 남김) |
| `judge_usage` | 호출별 토큰 |

## 배포 전 확인

- Gemini 키 2개(analysis·rubric)를 학생 대화용과 **다른 Google 프로젝트**에서 받아 `/opt/lit/app.env`에 넣습니다. 무료 프로젝트 여러 개를 쓰는 것이 약관에 맞는지 확인해 주세요.
- 실제 쓸 Gemini Flash-Lite로 질문 용도·루브릭 정확도를 아직 확인하지 않았습니다. 실제 제출물 30~50개를 교사 채점과 비교해 주세요.
- 학생 글이 외부 모델로 전송됩니다. 개인정보 처리방침·동의 화면에 판정 목적의 전송이 적혀 있는지 확인해 주세요. 모델에는 학생 이름 대신 내부 id만 보냅니다.
