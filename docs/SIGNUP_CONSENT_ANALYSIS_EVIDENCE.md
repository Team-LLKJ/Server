# 가입 동의·분석 근거·구간·실시간 알림·비동기 ZIP

feature/signup-consent-analysis-evidence 구현·미배포. 최신 develop ea8be76 기반. 백엔드와 서버 배포 스크립트만 변경하며 Web 브랜치는 수정하지 않습니다.

## 1. 회원가입 동의 (필수 계약 변경)

인증 없이 `GET /api/policies/current`:

```json
{"termsVersion":"v1","privacyVersion":"v1"}
```

환경 설정 `lit.policies.terms-version/privacy-version`의 현재 값입니다. 문서 본문은 프런트의 실제 약관·개인정보 처리방침과 맞춰 표시해야 합니다. 버전을 하드코딩하지 마세요.

`POST /api/auth/signup/teacher`:

```json
{
  "username":"teacher01","password":"Example-password-123","email":"teacher@example.com","name":"교사",
  "emailVerificationToken":"메일 인증 후 받은43자리토큰",
  "termsVersion":"v1","termsAccepted":true,"privacyVersion":"v1","privacyAccepted":true
}
```

가입 비밀번호는 8~20자입니다. 네 동의 필드가 추가로 필수입니다. 누락/형식 오류400 INVALID_REQUEST, 둘 중 false면400 CONSENT_REQUIRED, 현재 문서 버전과 다르면409 POLICY_VERSION_MISMATCH입니다. 불일치는 이메일 인증 토큰 소비 전에 검사합니다. 사용자 생성·이메일 인증 소비·두 동의 레코드는 같은 트랜잭션이며 저장 실패 시 전부 롤백됩니다. 두 문서는 같은 서버 동의 시각을 기록합니다. 가입 직후 로그인한 사용자의 GET /api/users/me/consents는 requiresAcceptance=false입니다. 기존 계정의 동의를 소급 생성하지 않습니다. 기존 POST consents API의 버전/거절 규칙·중복 요청 시 시각 보존은 유지합니다.

**프런트가 새 필드를 보내지 않으면 가입이 거절되므로 배포 시 함께 연결해야 합니다.**

## 2. 구조화된 분석 근거

기존 `/api/teacher/evaluations/{id}/participants/{pid}/analysis`의 indicators[]와 `/rubric-scores`의 criteria[].ai에 reasons를 추가합니다. 기존 reason 문자열과 evidence의 기존 식별자·note 필드는 보존합니다.

```json
{
  "reason":"기존 요약 문자열",
  "reasons":[
    {"type":"CREDIT","text":"AI 질문 6건 중 4건이 학습·탐색 목적 분류에 포함됐습니다."},
    {"type":"DEDUCTION","text":"답안 작성 요청 2건이 목적 적절성 산식에 반영됐습니다."}
  ],
  "evidence":[
    {"conversationId":73,"paragraphId":null,"eventId":null,"note":"답안 작성 요청으로 분류되어 목적 적절성 산식에 반영됩니다.",
     "type":"DEDUCTION","messageRole":"USER","quote":"답안을 대신 작성해줘.","reason":"답안 작성 요청으로 분류되어 목적 적절성 산식에 반영됩니다."}
  ]
}
```

- type은 CREDIT/DEDUCTION. 서버의 실제 규칙 산식과 관측 수치에서 생성하며 reason 문자열의 키워드로 추정하지 않습니다.
- quote는 본인 저장 대화의 questionText/answerText에서 그대로 발췌합니다(최대2000 UTF-16 단위). messageRole=USER면 학생 질문, AI면 해당 응답에 표시합니다. 메타데이터 conversationId와 역할을 함께 사용하세요.
- 검토 신호가 없다는 사유의 AI 응답 인용은 '검토 대상 응답'을 가리킬 뿐 응답 자체가 잘못됐다는 의미가 아닙니다.
- 지표별 대화 근거는 최대100개입니다. 저장 기록 부족으로 추천을 보류하는 항목은 level/value=null이며 부족을 0점·감점 확정으로 만들지 않습니다.
- 질문 길이/후속 참조·유사도 수정 신호 등 **기존 규칙 기반 지표**를 설명합니다. 의미 기반 행동 평가는 별도 process-grading V2입니다. 두 결과의 단계 체계를 혼용하지 않습니다.
- 기존 산식은 요소 합산 후 clamp/단계 반올림이 있어 개별 요인에 최종 점수의 정확한 증감량을 배분할 수 없습니다. 선택 필드 scoreDelta는 임의 생성하지 않고 생략합니다. 단계·점수는 기존 level/score를 사용하세요.
- rubric-scores에 summary를 추가해 analysis.summary와 같은 수치·가점/감점 요인 종합 문장을 제공합니다.
- 모델은 LIT_RULE_BASED_EXPLAINED_V2. 이전 캐시 분석은 조회 시 제출본에서 다시 계산하되 교사 입력·확정 총점은 변경하지 않습니다.

### 대화 연결

students/{pid}/conversations는 id="73-USER" 또는 "73-AI"와 conversationId=73을 이미 함께 제공합니다. participants/{pid}/conversations는 턴 단위 conversationId와 question/answer입니다. id에서 숫자를 추측하지 마세요.

## 3. 답안 유사도 구간

analysis.contribution에 segments, analysis에 answerTexts와 definition을 추가합니다.

```json
{
  "contribution":{
    "direct":32,"aiReferenced":22,"aiUsed":46,"basis":"TEXT_SIMILARITY_ESTIMATE",
    "segments":[{"questionId":"q1","start":0,"end":12,"type":"STUDENT","similarity":0.1,"effectiveSimilarity":0.1,"estimated":true,"answerVersion":2}]
  },
  "answerTexts":[{"questionId":"q1","text":"정규화된 답안 텍스트","answerVersion":2}]
}
```

예시는 구조 설명용입니다. 실제 end는 해당 text 범위 안입니다. start/end는 **answerTexts.text 기준 UTF-16, start 포함/end 제외**이며 JS String.slice와 같습니다. HTML 원문이나 별도 DOM.innerText에 적용하지 마세요. 서버는 HTML 제거·문단별 줄바꿈(LF)·표 셀의 ` | ` 구분을 사용합니다. 구간은 문장 경계로 나누되 기존 문단의 유사도 분류를 상속합니다. 문단 사이 LF는 비율 계산에서 제외합니다. 구간 길이 합과 기존 문단 글자 수 가중 비율이 일치하도록 구성합니다.

기본 정의:

- direct/STUDENT: 유효 유사도 <0.4, **낮은 유사도 추정 비중**. 실제 학생 작성 판정이 아닙니다.
- aiReferenced/AI_REFERENCED: 0.4 이상0.8 미만, 부분 유사도 추정 비중.
- aiUsed/AI_USED: 0.8 이상, 높은 유사도 추정 비중. 실제 복사 붙여넣기 판정이 아닙니다.
- 유사도는 공백·구두점 제거/소문자화 후 답안 문단의3-gram이 본인 AI 응답에 포함된 비율입니다. 짧은 문단은 부분 문자열 비교를 사용합니다. 원 similarity>0이고 같은 문항에 AI_ANSWER_COPY 기록이 있으면0.15를 더해 최대1인 effectiveSimilarity로 분류합니다.
- AI 대화가 없어도 낮은 유사도 비중이100%일 수 있으며 저자 신원을 증명하지 않습니다. STUDENT라는 구간 코드도 estimated=true입니다.
- 문단 UTF-16 글자 수로 가중하고 최대 잔여 방식으로 정수 퍼센트 합100을 만듭니다. 빈 답안은0/0/0 및 빈 segments입니다.

권장 화면 용어는 **낮은/부분/높은 AI 응답 유사도**입니다. '학생 직접 작성/AI 사용/복사 붙여넣기'라는 확정적인 작성자·행동 표시는 맞지 않습니다. /activity.studentContribution은 계속 null입니다.

실제 서버 설정은 교사 인증으로 `GET /api/teacher/evaluations/{id}/analysis/definition`에서 확인합니다. 각 저장 분석의 definition에도 분석 시점 기준값을 보존합니다. 환경 설정으로 임계값이 바뀔 수 있습니다. 원문·구간과 의미 기반 V2 기여 점수는 별개입니다.

## 4. 붙여넣기 신고

기존 `POST /api/student/evaluations/{id}/events` 또는 `/api/evaluations/{id}/events`, X-Participant-Token:

```json
{"type":"PASTE","occurredAt":"2026-11-08T08:00:00Z","questionId":"q1","length":12,"start":0,"end":12,"answerVersion":2}
```

PASTE/length는 기존에도 받습니다. start/end/answerVersion을 새로 추가했습니다. 정확한 구간을 보내려면 **답안 저장이 성공한 뒤** 저장된 version과 서버 정규화 텍스트 기준 구간을 보내세요. 범위 세 필드를 보내면 모두 필요하고 PASTE+questionId가 필수입니다. 구버전 충돌409 PASTE_VERSION_CONFLICT, 범위/UTF-16 서로게이트 중간 위치/부분 필드400 INVALID_PASTE_RANGE. 음수·필드 형식은400 INVALID_REQUEST. 구간 없는 기존 이벤트는 호환합니다.

analysis.pasteEvents 및 activity.pasteEvents에 eventId/questionId/occurredAt/length/start/end/answerVersion/matchesFinalAnswer/clientReported를 제공합니다. 최종 제출본과 버전·구간이 맞을 때만 matchesFinalAnswer=true입니다. 과거 붙여넣기가 이후 편집된 답안의 최종 구간이라고 주장하지 않습니다. clientReported=true는 학생 기기에서 보낸 신고이며 실제 외부 출처·부정행위 확정은 아닙니다. PASTE는 경고가 아니고 기존 이벤트12종 합산60건/분입니다. 프런트가 paste를 감지해 전송해야 합니다.

## 5. 사용 방법·학생 상태

activity.usage는 UNCLASSIFIED/neutral을 제외하고 남은 분류 건수 기준0.1% 최대 잔여 방식으로 합계100%를 구성합니다. 분류가 전혀 없으면 []이며 unclassifiedCount에 제외된 미분류 수를 표시합니다. 없는 분류를 임의로 만들어100%로 표시하지 않습니다. 50% 이상 높음,20% 이상50% 미만 보통,20% 미만 낮음입니다. 이 비중은 복수 목적 분류 건수 비중이며 실제 작성 비중이 아닙니다. analysis.usage의 단일 우선순위 질문 분류와 activity의 복수 목적 키워드 분류는 기존 계약대로 구분합니다.

activity 복수 목적 키워드는 아래와 같습니다(소문자화 후 각 조건을 독립 검사). 여러 조건에 맞으면 여러 분류에 포함되며 아무 조건에도 맞지 않으면 미분류입니다.

| 분류 | 키워드 |
|---|---|
| 아이디어 탐색 | 아이디어, 브레인, 발상, idea |
| 자료 조사 | 찾아, 검색, 자료, 출처, 조사 |
| 구조화 | 구조, 개요, 목차, 정리, 구성 |
| 검증 | 검토, 확인, 맞아, 맞는, 오류, 검증 |
| 답안 수정 | 수정, 고쳐, 다듬, 개선, 피드백, 교정, 맞춤법 |
| 답안 생성 요청 | 답안, 정답, 대신, 작성해, 써줘, 풀어, 완성 |
| 개념 도움 | 설명, 개념, 힌트, 이해, 왜 |

학생 목록의 status는 제출 시 SUBMITTED를 우선하며 미제출은 WRITING입니다. submissionStatus/submittedAt과 일치시킵니다. 경고를 status=WARNING으로 덮어쓰지 않습니다. 학생 목록 두 형식(submissions/students)에 lastWarningAt(서버 기록 시각)과 기존 warningCount를 함께 제공합니다. activityWarning은 과거 경고 존재 여부로 유지하며 잠깐 표시할 배지는 lastWarningAt/이벤트 ID를 기준으로 처리하세요.

## 6. 실시간 제출·경고 SSE

`GET /api/teacher/evaluations/{id}/live-events?afterId=0`, Authorization Bearer 필수. Last-Event-ID 헤더가 있으면 afterId보다 우선합니다. 기존 허용 프런트 Origin에 한해 이 헤더의 CORS 전송도 허용합니다. 교사 소유/공유 권한을 연결 시 및 매 조회마다 확인하며 토큰 만료·권한 해제 시 연결을 닫습니다. 토큰을 URL에 넣지 않습니다. 브라우저 기본 EventSource는 Authorization 헤더를 설정하지 못하므로 fetch 기반 SSE 읽기나 헤더를 지원하는 클라이언트를 사용하세요.

```text
id: 123
event: warning
data: {"eventId":123,"participantId":"pid","nickname":"학생","type":"WINDOW_LEAVE","occurredAt":"2026-11-08T08:00:00Z"}
```

submission과 warning 이벤트를 영속 learning_events에서 순서대로 재생합니다. occurredAt은 서버 기록 시각입니다. 이벤트 ID로 중복 처리 방지·재접속 재생을 구현하세요. 기본 afterId=0은 과거 기록도 재생합니다. 연결당 한 번에200개, 다음 묶음은 다음 조회에서 이어받습니다. 서버1초 주기 확인,20초 keepalive, 연결120초 후 재연결, 계정10개/서버200개 연결 제한(429 SSE_LIMIT). X-Accel-Buffering:no로 프록시 버퍼링을 끕니다. 기존 폴링 API는 유지합니다.

## 7. 비동기 ZIP

기존 동기 GET reports/archive 유지. 신규:

- `POST /api/teacher/evaluations/{id}/report-archive-jobs?format=HWP` →202
- `POST /api/teacher/assessments/{id}/report-archive-jobs` (folders 별칭 동일)
- `GET /api/teacher/report-archive-jobs/{jobId}`
- `GET /api/teacher/report-archive-jobs/{jobId}/download`

format/includeConversations는 기존 ZIP과 같습니다. 응답은 id/status/errorCode/createdAt/completedAt/expiresAt/downloadUrl. PENDING→RUNNING→DONE 또는 FAILED. DONE일 때만 인증이 필요한 상대 downloadUrl을 제공합니다. 요청한 교사만 작업을 조회하며 현재 평가/공유 권한도 다시 확인합니다. 다른 계정 작업404 ARCHIVE_JOB_NOT_FOUND. 계정당 진행 중3개 초과429 ARCHIVE_JOB_LIMIT. 기존 최대200명/100MiB·manifest·HWP 자동 생성 정책 유지.

완료 파일은1시간 후 만료·정리합니다. 만료되거나 재시작으로 파일이 없어졌으면 EXPIRED, 다운로드410 ARCHIVE_NOT_READY. 준비 전은409 ARCHIVE_NOT_READY. 오래 중단된 RUNNING 작업은15분 후 FAILED/WORKER_INTERRUPTED로 전환합니다. 동일 job 다운로드는 만료 전 반복 가능합니다. 워커는 저장된 요청자 계정 활성 상태·현재 공유 권한을 확인합니다. 앱 임시 파일은 배포 재시작 시 보존이 보장되지 않으므로 만료 응답이면 새 작업을 요청하세요.

## 8. .mjs MIME

서버 CI/CD의 배포 단계에 configure-report-mjs-nginx.py를 추가했습니다. 기존 LIT Web SPA 설정만 식별하고 정적 .mjs를 text/javascript로 내려주는 regex location을 추가합니다. /api/·/actuator/의 ^~ 프록시는 유지하고 없는 모듈은 SPA HTML 대신404를 반환합니다. 모듈은 no-cache로 최신 파일을 확인합니다. nginx -t 및 reload 실패 시 기존 파일을 복원합니다. Web 저장소 변경은 없습니다. 실제 운영 설정 변경은 이 브랜치 배포 시 실행되며 현재 개발만으로 운영에 적용됐다고 주장하지 않습니다.
