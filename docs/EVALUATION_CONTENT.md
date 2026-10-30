# 평가 내용 저장·학생 공유

`evaluationContent`는 평가 전체의 기준·규칙을 담는 일반 텍스트입니다. 개별 문항의 `content`와 별개입니다. 최대 10,000자이며 줄바꿈과 공백을 그대로 저장합니다. 프런트에서는 텍스트로 출력하고 줄바꿈을 유지하세요 (`white-space: pre-wrap`). HTML 실행은 지원하지 않습니다.

## 생성 시 저장

기존 `POST /api/teacher/evaluations` 요청에 선택 필드를 추가합니다. 교사 Bearer 토큰이 필요합니다.

```json
{
  "title": "수학 수행평가",
  "expiresInMinutes": 60,
  "evaluationContent": "평가 기준: 풀이 근거를 제시하세요.\nAI는 힌트 확인에만 사용하세요."
}
```

생성 응답 형식은 기존과 같습니다. 생략하거나 null이면 빈 문자열로 저장합니다. 기존 DB의 평가도 빈 문자열로 초기화하며 기존 데이터는 유지합니다.

## 수정 및 조회

| 메서드 | 경로 | 인증 |
| --- | --- | --- |
| PUT | `/api/teacher/evaluations/{id}/content` | 소유 교사 Bearer 토큰 |
| GET | `/api/teacher/evaluations/{id}/content` | 소유 교사 Bearer 토큰 |
| GET | `/api/evaluations/{id}/content` | 해당 평가의 `X-Participant-Token` |

PUT 요청:

```json
{"evaluationContent":"평가 기준: 근거를 제시하세요.\n제한 시간 내 제출하세요."}
```

세 API의 성공 응답 (HTTP 200):

```json
{
  "evaluationId": "평가 UUID",
  "evaluationContent": "평가 기준: 근거를 제시하세요.\n제한 시간 내 제출하세요."
}
```

빈 문자열로 기존 내용을 지울 수 있습니다. PUT에서는 필드 생략·null과 10,000자 초과를 400으로 거절합니다. 만료되지 않은 WAITING 평가에서만 수정할 수 있습니다. 시작·종료 후 수정은 409 `EVALUATION_CONTENT_LOCKED`, 만료된 대기 평가는 410 `PIN_EXPIRED`입니다. 다른 교사의 평가에는 404를 반환합니다.

학생은 PIN·닉네임으로 참가한 뒤 받은 참가자 토큰으로 조회합니다. 다른 평가의 토큰이나 나간 참가자의 토큰으로는 조회할 수 없습니다 (401). 참가 후 대기 중에도 조회 가능하며 제출·평가 종료 후에도 참가 상태가 유효하면 조회할 수 있습니다.

기존 학생 풀이 조회 `GET /api/evaluations/{id}/questions` 응답에도 `evaluationContent` 필드가 추가됩니다. 풀이 화면은 이 필드를 표시하면 별도 호출이 필요 없습니다. 교사 상세 조회 `/api/teacher/evaluations/{id}`의 응답은 기존과 같으므로 내용 표시에는 `/content`를 호출하세요.

## Postman

컬렉션의 ‘평가 내용 저장·공유’ 폴더를 사용하세요. 기존 흐름으로 평가 생성·학생 참가 후, 평가 시작 전에 저장 요청을 실행하세요. `baseUrl`, `teacherAccessToken`, `evaluationId`, `participantToken` 변수를 사용합니다.
