# 학생 재접속·답안 복귀

## 공개 재접속 API

`POST /api/participation/reconnect`

```json
{"evaluationId":"반 회차 또는 독립 평가 ID","participantToken":"최초 참가 때 받은 64자리 토큰"}
```

교사 Bearer 토큰·PIN·닉네임이 아니라 최초 참가자의 토큰으로 본인 확인합니다. 요청 본문이므로 토큰을 URL에 넣지 않습니다. 토큰 원문을 로그에 출력하지 마세요. 기존 참여 API와 같은 IP당30회/분 제한을 공유합니다.

200 응답 구조:

```json
{
  "evaluationId":"평가 ID",
  "participantId":"기존 참가자 ID",
  "nickname":"학생",
  "lobby":{
    "evaluationId":"평가 ID","title":"평가 제목","state":"STARTED","screen":"SOLVING",
    "participantCount":1,"participants":[{"id":"기존 참가자 ID","nickname":"학생","presence":"CONNECTED"}],
    "endsAt":"2026-11-05T15:00:00Z","serverNow":"2026-11-05T14:30:00Z"
  },
  "readOnly":false,
  "workspace":{
    "evaluationId":"평가 ID","participantId":"기존 참가자 ID","status":"IN_PROGRESS","readOnly":false,
    "aiPolicy":"DISABLED",
    "questions":[{"question":{"id":"문항 ID","position":1,"title":"문항","content":"문제","minChars":null,"maxChars":null},"answer":{"questionId":"문항 ID","content":"마지막 저장 답안","version":2,"updatedAt":"2026-11-05T14:29:00Z"}}],
    "evaluationContent":"안내","guidelines":"안내","aiLevel":1,"aiLevelDescription":"Level 1 안내",
    "criteria":[],"antiCheat":[],"assessmentId":null,"className":null
  }
}
```

workspace는 기존 `GET .../workspace`와 같은 구조입니다. 대기 중이면 workspace=null, lobby.screen=WAITING, readOnly=true입니다. 평가 ID는 틀 assessmentId가 아닌 classId/evaluationId입니다.

- 토큰은 기존 해시 또는 이전 호환 방식으로 검사하며 참가자의 active=false 상태도 본인 인증합니다.
- 성공 시 같은 참가자의 active=true·last_seen을 갱신합니다. 기존 토큰을 유지하고 응답에는 토큰을 다시 반환하지 않습니다.
- 종료·만료된 PIN을 요구하지 않습니다. 종료되었어도 유효한 기존 참가자 토큰으로 읽기 전용 복귀가 가능합니다.
- 진행 중이면 원래 종료 시각을 유지합니다. 시간·답안·버전·제출본을 재접속 때문에 초기화하지 않습니다.
- 마감이 지났다면 기존 자동 제출을 처리하고 SUBMITTED/읽기 전용으로 반환합니다. 이미 수동 제출한 학생도 SUBMITTED입니다.
- 평가 행 잠금으로 시작·종료·저장과 직렬화합니다. 재요청해도 참가자·제출 기록을 중복 생성하지 않습니다.
- 잘못된 형식·토큰 누락400, 다른 평가/잘못된 토큰401 INVALID_PARTICIPANT_TOKEN, 삭제·없는 평가404 EVALUATION_NOT_FOUND, 호출 초과429 RATE_LIMITED입니다.
- 토큰을 잃은 경우 PIN·동일 닉네임만으로 기존 답안을 넘기지 않습니다. 별도 신원 복구·교사 승인 기능은 이번 범위가 아닙니다.

## Web 연동 시 필요한 처리 (이번 변경에 Web 파일 수정 없음)

1. 최초 join에서 받은 토큰·evaluationId를 브라우저 종료 후에도 접근할 수 있도록 보관하는 정책이 필요합니다. 현재 Web의 sessionStorage 토큰은 창 종료 후 복귀를 보장하지 못합니다. 브라우저 저장 수단·만료·로그아웃 정리와 XSS 방어를 함께 검토하세요.
2. 초안은 평가·참가자·문항별로 content, baseVersion, baseContent, 출처/AI 참조, 수정 시각과 마지막 문항을 보관합니다. 저장 실패·인증 실패만으로 초안을 지우지 않습니다.
3. 새로고침·연결 복구·이어서 작성하기에서 위 API를 호출합니다. WAITING은 대기실, readOnly=true는 제출/종료된 서버 답안, 진행 중이면 workspace와 로컬 초안을 비교합니다.
4. 로컬 baseVersion이 서버 version과 같으면 초안을 복원할 수 있습니다. 서버 content가 로컬 초안과 같다면 저장 응답 유실 가능성을 확인해 서버 버전을 반영합니다. 양쪽이 다르면 충돌 UI에서 서버본·로컬본을 보여주고 사용자가 선택해야 합니다. 버전만 갱신해 자동 덮어쓰지 않습니다.
5. 저장 요청의 content·revision을 캡처합니다. 응답이 도착해도 그사이 추가 입력이 있으면 해당 입력은 계속 미저장으로 유지합니다. 실패하면 입력 없이도 순차 재시도하며, 재연결 후에는 먼저 서버 상태/버전을 재확인합니다.
6. offline/online 및 실제 요청 실패/성공을 조합해 끊김·재연결 중·연결됨을 표시합니다. 브라우저 online 값만으로 서버 연결 성공을 단정하지 않습니다.
7. 제출·시간 종료 후 로컬 초안을 서버에 재저장하지 않습니다. 읽기 전용 서버 제출본과 미전송 초안을 구분하고, 자동으로 새 참가자를 생성하거나 토큰을 삭제해 PIN 화면으로 보내지 않습니다.

브라우저 초안 보관·충돌 선택·마지막 문항 복원·이어쓰기 화면은 프런트 구현이 필요합니다. 이 API 배포만으로 Web 화면이나 저장 재시도 동작이 바뀌지는 않습니다. Web 저장소는 이번 작업에서 수정하지 않았습니다.

## 제출한 학생 차단 (2026-11-14)

제출(수동·자동 제출 모두)한 참가자 토큰으로는 평가 화면과 본인 기록을 볼 수 없습니다. 나가기 호출이 실패해도 같습니다.

- 401 `INVALID_PARTICIPANT_TOKEN`("제출한 참가자는 평가 화면과 기록을 다시 볼 수 없습니다."): 문항·답안 조회·저장, workspace, AI 질문·대화 조회, 학습 이벤트, 제출 미리보기·제출본 조회, `/me`·정책 동의, 문항 내용, `POST /api/participation/reconnect`, 토큰을 넣은 `POST /api/participation/join`.
- 그대로 되는 것: `GET /state`, `POST /heartbeat`, `POST /leave`(응답은 대기실 정보뿐, `screen=SUBMITTED`), 같은 `reviewToken`으로 다시 보낸 `POST /submission`(제출 재시도. 응답에 AI 활용 기록은 없음).
- 예외: 종료·마감으로 **자동 제출**된 학생은 `lit.evaluation.end-grace-seconds` 유예 시간 동안만 계속 저장할 수 있습니다(기존 동작). 직접 제출한 학생은 바로 막힙니다.
- 다시 들어오는 순간 마감 처리로 자동 제출되면 `200`, `readOnly=true`, `lobby.screen=SUBMITTED`, `workspace=null`이고 이후 호출은 401입니다.
- 보고서(`POST /api/evaluations/{id}/reports`, `GET /report`, `/report/pdf`, `/report/hwp`, `/report.hwp`)와 AI 활용 기록(`GET·PUT /api/evaluations/{id}/ai-disclosure`, `/api/student/evaluations/{id}/ai-disclosure`)은 참가자 토큰으로는 403 `TEACHER_ONLY`입니다. 교사 API(`/api/teacher/evaluations/{id}/students/{pid}/...`)만 씁니다.

