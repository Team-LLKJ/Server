# 문항별 답안 임시저장·불러오기

기존 답안을 참가자·문항별로 DB에 저장합니다. 임시저장은 최종 제출이 아니며 별도 초안 테이블을 만들지 않습니다. 서버 재시작 후에도 같은 참가자 토큰으로 불러옵니다. 다른 문항의 답안에는 영향을 주지 않습니다.

## 경로·인증

`X-Participant-Token: 참가자 토큰` 헤더를 사용합니다. 아래 두 경로는 동일하게 동작합니다.

- `/api/evaluations/{id}/questions/{questionId}/answer`
- `/api/student/evaluations/{id}/questions/{questionId}/answer`

### PUT — 임시저장 (기존 API)

```json
{"content":"<p>작성 중인 답안</p>","expectedVersion":0,"source":"DIRECT"}
```

처음 저장은 expectedVersion=0, 이후에는 GET 또는 PUT 응답의 version을 보냅니다. 빈 문자열로 답안을 지울 수 있습니다. 기존 source·aiRequestId 검증도 적용됩니다. AI 참고 답안은 본인의 해당 문항 완료 대화 ID를 보내야 합니다.

### GET — 해당 문항 저장본 조회 (추가)

요청 본문 없이 호출합니다. PUT 성공과 GET 성공은 모두 200이며 같은 형식입니다.

```json
{
  "questionId":"문항 ID",
  "content":"<p>작성 중인 답안</p>",
  "version":1,
  "updatedAt":"2026-11-03T02:00:00Z"
}
```

저장 전에는 content="", version=0, updatedAt=null입니다. 빈 답안을 명시적으로 저장했다면 version과 updatedAt이 존재합니다. GET은 답안 버전·저장 시각·학습 이벤트를 변경하지 않습니다. 문항 목록 전체는 기존 workspace, 문항 설명과 답안은 기존 GET .../questions/{questionId}로 조회합니다.

## 프런트 연동

1. 문항을 열 때 GET으로 저장본을 읽고 content와 version을 보관합니다.
2. 변경된 답안을 PUT으로 보내고 성공 응답의 정리된 content·version·updatedAt을 반영합니다.
3. 같은 문항 요청은 순차 처리하고 문항 전환 전 저장 성공을 확인합니다. 자동저장에도 같은 PUT을 사용합니다.
4. 409 ANSWER_VERSION_CONFLICT이면 미저장 입력을 보존하고 GET으로 최신 답안을 조회해 사용자가 변경 내용을 확인하게 합니다. 최신 버전으로 자동 덮어쓰기하지 마세요.
5. 최종 제출은 기존 submission-preview → POST submission 흐름을 사용합니다. 수동·자동 제출에는 마지막으로 저장된 답안이 포함됩니다. 브라우저에만 있는 입력은 포함되지 않습니다.

## 제한·오류

- 시작 전 GET·PUT은 409 EVALUATION_NOT_STARTED입니다.
- 제출 후에도 본인 답안 GET은 가능하지만 PUT은 409 ALREADY_SUBMITTED입니다. 종료·만료 후 저장 제한과 선택적 유예는 [TIME_LIMITS.md](TIME_LIMITS.md)의 기존 정책을 따릅니다.
- 참가자 토큰 누락·불일치는 401, 해당 평가의 문항이 아니면 404 QUESTION_NOT_FOUND입니다. 다른 참가자의 답안은 조회·변경할 수 없습니다.
- HTML 정리·UTF-8 1MiB 제한, 문항 최대 글자 수 제한은 기존 PUT과 같습니다. 최소 글자 수 미달 초안은 저장 가능하며 최종 제출 시 검사합니다.
- 버전 충돌·검증 실패 시 기존 저장 답안과 버전은 유지됩니다. 여러 탭에서 같은 버전을 저장하면 하나만 성공합니다.
