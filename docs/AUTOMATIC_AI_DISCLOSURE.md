# 시스템 자동 AI 사용 기록

학생 입력을 대체합니다. 기존 tools / usedParts / reason 문자열을 유지하고 다음 필드를 추가합니다.

- status: PENDING | READY | FAILED. 제출 전 PENDING은 제출 시 생성 예정이라는 뜻입니다. 현재 구현은 외부 AI 호출 없는 동기 규칙 기반 생성입니다.
- source: SYSTEM_GENERATED, editable: 항상 false
- updatedAt: 자동 기록 내용이 생성되거나 마지막으로 바뀐 시각입니다.
- conversationIds: 판단에 사용한 ai_turns.id 목록 (채팅의 conversationId와 동일)
- evidence: [{conversationId, questionId, purpose, messageRole: "USER", quote, matchingAnswerParagraphs, answerVersion}]. quote는 기록된 학생 질문 원문입니다.
- errorCode / failureReason: FAILED 시 DISCLOSURE_GENERATION_FAILED와 안전한 설명. 실패도 저장하고 조회 시 자동 재시도하지 않습니다.

GET /api/student/evaluations/{evaluationId}/ai-disclosure   (2026-11-14 없앰: 참가자 토큰은 403 TEACHER_ONLY)
GET /api/teacher/evaluations/{evaluationId}/students/{participantId}/ai-disclosure
(기존 participants 별칭도 유지)

학생 요청은 X-Participant-Token, 교사 요청은 기존 Bearer 인증과 평가 소유권 검사를 사용합니다.
학생 PUT 동일 경로는 호환성 유지용입니다. 기존 유효한 요청 본문은 검증하지만 내용을 저장하지 않고 현재 읽기 전용 결과를 반환합니다. 새 클라이언트는 호출하지 마세요.

제출 확정 시 검토 토큰을 검증한 후 한 번 생성하고 제출 스냅샷에 함께 저장합니다. 토큰은 확정 전 답안·대화 검토용이며 생성 시각은 서버가 확정 후 추가하는 메타데이터입니다. 시간 만료·교사 종료 시 자동 제출에도 생성합니다. 시작 전 대기실 종료는 사용 기록만 생성하고 제출로 만들지 않습니다. 기존 제출은 첫 조회 시 제출 스냅샷을 이용해 한 번 생성합니다. 기존 수동 기록과 저장된 보고서 버전은 삭제하지 않습니다.

- `tools`는 완료 응답의 도구·모델을 중복 제거해 줄바꿈으로 연결합니다.
- `usedParts`는 질문마다 한 줄입니다. 용도는 채점 화면과 같은 분류를 사용합니다. 교사 확정, 저장된 LLM 분류, 키워드 대체 순으로 적용합니다.
- `reason`은 답안 문장마다 한 줄입니다. AI 답변 인용, 표현 일치, 학생이 먼저 쓴 표현, 교사 확정 등 저장된 분석 근거를 표시합니다.
- 용도 분류·문장 분석 저장, 재채점, 교사 용도·출처 수정 후 갱신합니다. 기존 제출은 조회할 때 최신 분석을 반영합니다. 기록 생성에 별도 LLM 호출이 없습니다.
- 새 PDF·HWP 보고서는 같은 기록을 사용합니다. 대화 원문 포함 옵션으로 질문·AI 답변 인용을 출력할 수 있고, 기존 저장 보고서 버전은 보존됩니다.

교사 조회 응답 예시 (식별자는 예시입니다):

```json
{
  "evaluationId": "evaluation-id",
  "participantId": "participant-id",
  "tools": "LIT 내장 AI (gemini / model-name)",
  "usedParts": "문항 2 – 이해·조사: \"광합성이 뭐야?\"",
  "reason": "문항 2 답안 1번째 문장 – 학생 문장으로 분류",
  "updatedAt": "2026-11-08T09:00:00Z",
  "editable": false,
  "status": "READY",
  "source": "SYSTEM_GENERATED",
  "errorCode": null,
  "failureReason": null,
  "conversationIds": [73],
  "evidence": [{
    "conversationId": 73,
    "questionId": "question-id",
    "purpose": "이해·조사",
    "messageRole": "USER",
    "quote": "광합성의 개념을 설명해줘",
    "matchingAnswerParagraphs": [],
    "answerVersion": 2
  }],
  "frozen": true
}
```
