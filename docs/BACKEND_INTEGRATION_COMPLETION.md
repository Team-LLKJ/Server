# 프런트 연동 누락 보완 (message 11)

feature/backend-integration-completion 구현·미배포. feature/grading-feedback548a575를 포함한 기반이며 로고·공통 서식은 별도 PR #48로 develop에 반영했습니다. Web 브랜치는 수정하지 않습니다. 선택 사항인 보안 쿠키·인증 설정은 변경하지 않습니다.

## 요청 대조

| 요청 | 처리 |
|---|---|
| 반·폴더 일괄 보고서 | ZIP API 신규, 미생성 보고서 자동 생성, 실패·미제출은 manifest |
| PDF/HWP 공통 디자인 | 기존 공통 템플릿·로고·#007BFF 유지 |
| feedback 저장·보고서 | 기존 API 유지(근거만·단계만 저장, 교사 근거 우선), 회귀 검증 |
| conversationId 연결 | students 대화의 기존 id(예:12-USER) 유지, conversationId=12 추가 |
| 이벤트 | FULLSCREEN·DEVTOOLS 별칭 추가, 기존10종 포함 총12종 합산60건/분 |
| AI 사용 설명 | 기존 학생 PUT 유지, 제출본에 동결·교사 조회·보고서 표 추가 |
| 재접속 | 기존 API 유지, 잘못되거나 없는 토큰은401로 통일 |
| 대기실 | 기존 assessment/classes/{id}/lobby와 lobbyState 유지 |
| 알림 삭제·비번 찾기 | 기존 배포된 API 유지 |
| 안내 동의 | 서버 시각·안내 버전별 저장, workspace.policyAccepted 추가 |
| 평가 생성 설정 | criteria·antiCheat 신규 허용, 기존 검증·정규화 재사용 |
| 참가 정보 me | 학생 토큰으로 서버 참가 정보 조회 |
| 제출 후 보고서 | 기존 토큰의 본인 보고서 전용 접근 유지(퇴장 후 제출자 포함) |

## 1. ZIP 다운로드

교사 Bearer Access Token 필요:

- `GET /api/teacher/evaluations/{id}/reports/archive?format=PDF&includeConversations=false`
- `GET /api/teacher/assessments/{assessmentId}/classes/{classId}/reports/archive`
- `GET /api/teacher/assessments/{assessmentId}/reports/archive`
- 폴더 별칭: `GET /api/teacher/folders/{assessmentId}/reports/archive`

폴더 ID는 서버의 assessmentId(수행평가 틀)입니다. 브라우저 캐시의 임의 폴더 ID를 보내지 않습니다. 소유자와 공유 EDITOR만 접근할 수 있습니다. classId는 지정 assessment 소속인지 검사합니다. 생성 도중 권한 해제 시 다운로드를 시작하지 않습니다.

format은 PDF/HWP(대소문자 무관), 기본 PDF. includeConversations 기본false. 200 application/zip, Content-Disposition attachment; filename="lit-reports-pdf.zip" 또는 hwp. 반별 폴더/학생 순번-닉네임.pdf(hwp)와 manifest.json이 포함됩니다. 경로의 특수문자·슬래시를 제거하고 번호로 중복을 방지합니다.

대상 목록 조회 시 참가자 최대200명, 최종ZIP100MiB 제한(초과413 REPORT_ARCHIVE_LIMIT). 파일은 임시 디스크에 구성하고 전송 완료·중단 시 삭제합니다. 순차 생성이며 비동기 작업 큐는 아닙니다. 외부 AI를 호출하지 않습니다. 각 제출자의 최신 채점 자료로 보고서를 생성/재사용합니다. 미제출은 SKIPPED, 개별 생성 실패는 FAILED로 기록하고 나머지 학생을 계속 처리합니다. 권한 오류는 전체 요청을 거절합니다. 모든 항목이 미제출이어도 manifest를 담은 ZIP을 반환하므로 결과 건수를 확인하세요.

```json
{
  "format":"HWP","includeConversations":false,"total":2,"succeeded":1,"skipped":1,"failed":0,
  "items":[
    {"evaluationId":"class-id","participantId":"student-id","status":"DONE","file":"001-1반/001-학생.hwp"},
    {"evaluationId":"class-id","participantId":"other-id","status":"SKIPPED","code":"SUBMISSION_REQUIRED"}
  ]
}
```

오류: 400 INVALID_REPORT_FORMAT / INVALID_REQUEST, 401 인증 오류, 404 ASSESSMENT_NOT_FOUND/EVALUATION_NOT_FOUND(다른 교사 포함), 413 REPORT_ARCHIVE_LIMIT. FAILED code는 REPORT_EXPORT_FAILED 또는 기존 도메인 오류입니다. 내부 예외 원문은 노출하지 않습니다.

### 단건 HWP 최초 조회

기존 교사·학생 report/hwp와 report.hwp에서 **version 생략·저장본 없음·제출 완료**이면 생성 후 다운로드합니다. 이미 저장된 보고서는 그대로 재사용합니다. version 지정 시에는 자동 생성하지 않으며 미생성/없는 버전은404 REPORT_NOT_FOUND입니다. 미제출도 기존404를 유지합니다. 근거 변경 후 새 보고서가 필요하면 POST reports를 호출하세요. PDF의 기존 report.pdf 자동 생성 동작은 유지합니다.

## 2. 생성 설정

`POST /api/teacher/evaluations`에 기존 필드와 함께 아래를 보낼 수 있습니다.

```json
{"title":"수행평가","expiresInMinutes":60,"criteria":["비판적 검토","학생 기여"],"antiCheat":["TAB_LEAVE","FULLSCREEN","DEVTOOLS"]}
```

criteria는 제목 배열(최대10, 중복 없는1~100자) 또는 합계100점 상세 루브릭 배열입니다. 섞어 보내면400 INVALID_CRITERIA. antiCheat는 기존 계약대로 코드 배열 또는16KiB 이하 객체입니다. FULLSCREEN 설정 별칭은 FULLSCREEN_LEAVE로 정규화해 저장·조회·workspace에 반환합니다. 중복 정규 코드·잘못된 배열 코드는400 INVALID_ANTI_CHEAT. 생략한 설정은 기존 기본값을 유지합니다. 생성 검증 실패 시 평가·PIN·설정을 모두 롤백합니다.

## 3. 근거 연결·이벤트

`GET .../students/{pid}/conversations`의 메시지:

```json
{"id":"12-USER","conversationId":12,"role":"USER","content":"질문","questionId":"q1","createdAt":"2026-11-08T00:00:00Z","status":"COMPLETED","errorCode":null}
```

기존 id와 역할을 바꾸지 않습니다. AI evidence.conversationId=12로 찾아 **USER 역할의 conversationId=12** 메시지로 이동하세요. participants/{pid}/conversations는 이미 turn 형식의 conversationId를 제공합니다. requestId(학생 요청 재시도 키)와 conversationId(서버 대화 번호)는 다릅니다.

클라이언트 이벤트 12종: WINDOW_LEAVE, SHORTCUT_CAPTURE, WINDOW_BLUR, CAPTURE_SHORTCUT, COPY_ATTEMPT, FULLSCREEN_EXIT, FULLSCREEN_LEAVE, DEVTOOLS_OPEN, FULLSCREEN, DEVTOOLS, PASTE, AI_ANSWER_COPY. FULLSCREEN/DEVTOOLS도 경고로 집계하고 최초 경고 교사 알림·activity.warnings에 포함합니다. 원래 type 문자열을 보존하며 모두 합산60건/분입니다. 점수 자동 감점 없음. studentContribution=null, contribution.direct는 TEXT_SIMILARITY_ESTIMATE라는 기존 정책은 유지합니다.

## 4. AI 사용 설명

기존 학생 GET/PUT `/api/evaluations/{id}/ai-disclosure` 및 `/api/student/evaluations/{id}/ai-disclosure`를 사용합니다. PUT {tools,usedParts,reason}, 최대500/4000/4000자, 제출 전만 수정 가능.

교사 조회 신규: `GET /api/teacher/evaluations/{id}/students/{pid}/ai-disclosure` (participants 별칭도 지원). tools/usedParts/reason/updatedAt/frozen 반환. 학생 제출·자동 제출의 snapshot.aiDisclosure에 저장하고, 교사 상세 work.aiDisclosure 및 보고서 AI 활용 표에서 조회합니다. 새 제출본은 frozen=true입니다. 과거 제출본에 설명 스냅샷이 없으면 기존 저장된 ai_disclosures를 읽되 frozen=false로 표시합니다. 예전 기록을 제출 당시 동결됐다고 주장하지 않습니다. 과정 평가 새 제출본도 동결된 설명을 근거로 사용합니다. 기존 보고서 버전은 소급 수정하지 않습니다.

## 5. 참가 정보·동의

`GET /api/evaluations/{id}/me` 또는 `/api/student/evaluations/{id}/me`, X-Participant-Token 필수. 서버의 evaluationId/participantId/nickname/title/pin/evaluationState/status/readOnly/submitted/guidelines 및 policy 반환. 토큰 원문은 반환하지 않으며 접속 활성 상태를 변경하지 않습니다.

```json
{
  "evaluationId":"id","participantId":"pid","nickname":"학생","title":"평가","pin":"012345",
  "evaluationState":"WAITING","status":"WAITING","readOnly":true,"submitted":false,"guidelines":"평가 안내",
  "policy":{"policyAccepted":false,"policyAcceptedAt":null,"policyVersion":"64자리 SHA-256"}
}
```

`POST /api/evaluations/{id}/policy-acceptance` (student 별칭 동일), X-Participant-Token과 본문 `{policyVersion:"me 또는 workspace에서 받은64자리값"}` 필수. 200 policyAccepted/policyAcceptedAt/policyVersion. 같은 버전 반복 요청은 최초 서버 동의 시각을 유지합니다. 안내·AI 정책·criteria·antiCheat가 바뀌면 policyVersion이 바뀌고 새 동의가 필요합니다. 과거 버전 동의도 보존합니다. 400 INVALID_REQUEST, 409 POLICY_VERSION_CONFLICT, 신규 동의가 제출·종료 후면409 POLICY_ACCEPTANCE_CLOSED. 이미 동의한 버전 재요청은 멱등적으로 반환합니다.

workspace에도 최상위 policyAccepted/policyAcceptedAt/policyVersion을 추가합니다. 기존 참가자의 동의를 자동 추정·생성하지 않습니다. 이번 작업에서 동의를 답안 저장·제출의 강제 선행 조건으로 바꾸지는 않았습니다.

me/동의는 기존 active 참가 토큰을 요구하며 잘못된·다른 평가·퇴장한 토큰은401입니다. 탭 재접속은 기존 POST `/api/participation/reconnect` {evaluationId,participantToken}로 수행하고 유효한 기존 참가자를 복귀시킵니다. 형식이 틀리거나 누락·만료/다른 평가 토큰도401 INVALID_PARTICIPANT_TOKEN. evaluationId 자체의 누락/형식 오류는400. 제출·마감 후 readOnly로 복귀합니다.

제출 후 퇴장한 학생은 원래 토큰으로 본인 reports/report/PDF/HWP에 접근할 수 있습니다(기존 보고서 전용 검증). me나 workspace까지 허용하는 새 토큰을 발급하지 않습니다. 브라우저에서 토큰까지 삭제하면 서버가 신원을 복구할 수 없으므로 프런트는 필요한 자격 증명을 보존해야 합니다. 쿠키 전환은 이번 범위에서 제외했습니다.

## 6. 이미 있는 API

대기실 `POST /api/teacher/assessments/{assessmentId}/classes/{classId}/lobby`: state=READY, lobbyState=WAITING, PIN/QR 발급·반복 호출 PIN 유지. 별도 /start부터 타이머 시작.

알림 `DELETE /api/notifications/{id}`, `DELETE /api/notifications`: {deleted,unreadCount}. 비밀번호 재설정 `POST /api/auth/password-reset/request` {email}, /confirm {token,newPassword}. 운영 배포 여부와 신규 기능의 미배포 여부를 구분하세요. 프런트 확정 화면·목 데이터 제거·로컬 환경설정은 수정하지 않습니다.

## 구조화 근거·가입 동의 후속 계약

분석 reasons/정확한 quote, 정규화 답안 segments·PASTE 기록, 학생 상태/lastWarningAt, SSE와 비동기 ZIP은 [SIGNUP_CONSENT_ANALYSIS_EVIDENCE.md](SIGNUP_CONSENT_ANALYSIS_EVIDENCE.md)에 정의합니다. 기존 동기 ZIP·대화 ID·feedback 계약은 유지합니다.
