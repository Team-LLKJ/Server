# 시스템 자동 AI 사용 기록

학생 입력을 대체합니다. 기존 tools / usedParts / reason 문자열을 유지하고 다음 필드를 추가합니다.

- status: PENDING | READY | FAILED. 제출 전 PENDING은 제출 시 생성 예정이라는 뜻입니다. 현재 구현은 외부 AI 호출 없는 동기 규칙 기반 생성입니다.
- source: SYSTEM_GENERATED, editable: 항상 false
- updatedAt: 생성 시각. 저장 후 조회·재제출 시 바뀌지 않습니다.
- conversationIds: 판단에 사용한 ai_turns.id 목록 (채팅의 conversationId와 동일)
- evidence: [{conversationId, questionId, purpose, messageRole: "USER", quote, matchingAnswerParagraphs, answerVersion}]. quote는 기록된 학생 질문 원문입니다.
- errorCode / failureReason: FAILED 시 DISCLOSURE_GENERATION_FAILED와 안전한 설명. 실패도 저장하고 조회 시 자동 재시도하지 않습니다.

GET /api/student/evaluations/{evaluationId}/ai-disclosure
GET /api/teacher/evaluations/{evaluationId}/students/{participantId}/ai-disclosure
(기존 participants 별칭도 유지)

학생 요청은 X-Participant-Token, 교사 요청은 기존 Bearer 인증과 평가 소유권 검사를 사용합니다.
학생 PUT 동일 경로는 호환성 유지용입니다. 기존 유효한 요청 본문은 검증하지만 내용을 저장하지 않고 현재 읽기 전용 결과를 반환합니다. 새 클라이언트는 호출하지 마세요.

제출 확정 시 검토 토큰을 검증한 후 한 번 생성하고 제출 스냅샷에 함께 저장합니다. 토큰은 확정 전 답안·대화 검토용이며 생성 시각은 서버가 확정 후 추가하는 메타데이터입니다. 시간 만료·교사 종료 시 자동 제출에도 생성합니다. 시작 전 대기실 종료는 사용 기록만 생성하고 제출로 만들지 않습니다. 기존 제출은 첫 조회 시 제출 스냅샷을 이용해 한 번 생성합니다. 기존 수동 기록과 저장된 보고서 버전은 삭제하지 않습니다.

tools는 완료된 비어 있지 않은 응답의 provider/model 조합을 중복 제거해 줄바꿈으로 연결합니다. 실패 요청만 있으면 빈 문자열입니다. usedParts는 문항 번호와 질문 표현의 규칙 기반 목적 분류·횟수를 제공합니다. 최종 답안의 20자 이상 문단 전체가 해당 문항 AI 응답에 그대로 포함된 경우에만 matchingAnswerParagraphs(1부터 시작)에 문단 번호를 표시합니다. 이는 동일 문구 관측이며 실제 작성자 판정이 아닙니다. 근거 없이 특정 문단 위치를 만들어내지 않습니다. reason은 시스템 관측임을 명시합니다. 실제 의도·실제 작성자·외부 AI 사용은 확정하지 않습니다. 대화가 없으면 tools="", usedParts="LIT에 기록된 AI 대화가 없습니다."입니다.

분류는 기존 AssessmentAnalyzer.classify 규칙을 재사용합니다 (개념 확인, 자료 탐색, 아이디어 구체화, 답안 작성 요청, 검증 요청, 기타 요청). 자동 생성 설명은 학생의 자기 공개 근거(DISCLOSURE)로 채점기에 전달하지 않으며 투명성 점수에 자동 가산하지 않습니다. PDF/HWP 공통 보고서의 AI 사용 기록 표에도 동일 결과를 사용합니다.

교사 조회 응답 예시 (식별자는 예시입니다):

```json
{
  "evaluationId": "evaluation-id",
  "participantId": "participant-id",
  "tools": "LIT 내장 AI (gemini / model-name)",
  "usedParts": "시스템이 기록된 질문을 분류한 결과: 문항 2: 개념 확인 1건",
  "reason": "시스템 관측: 질문 1건, 완료 응답 1건, 실패·미완료 0건. 시스템 분류: 개념 확인 1건. 활용 목적은 질문 표현에 따른 규칙 기반 추정이며 실제 의도·답안 반영 위치를 확정하지 않습니다.",
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
    "purpose": "개념 확인",
    "messageRole": "USER",
    "quote": "광합성의 개념을 설명해줘",
    "matchingAnswerParagraphs": [],
    "answerVersion": 2
  }],
  "frozen": true
}
```
