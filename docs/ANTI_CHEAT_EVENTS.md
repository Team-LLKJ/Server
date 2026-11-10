# 부정행위 설정·학생 경고 이벤트 확장

## 설정 코드

`antiCheat` 문자열 배열에 아래4개 코드를 허용합니다. 중복·알 수 없는 코드·문자열이 아닌 배열 원소는400 `INVALID_ANTI_CHEAT`입니다. 기존16KiB 이하 JSON 객체 호환과 생략 처리 정책은 유지합니다.

| 설정 | 의미 | 학생 신고 이벤트 |
|---|---|---|
| TAB_LEAVE | 창 이탈 감지 | WINDOW_BLUR / WINDOW_LEAVE |
| PRINT_BLOCK | 인쇄 차단 설정 | 기존 정책 유지 |
| FULLSCREEN_LEAVE | 전체화면 이탈 감지 | FULLSCREEN_EXIT |
| DEVTOOLS | 개발자 도구 열림 감지 | DEVTOOLS_OPEN |

```json
{"antiCheat":["TAB_LEAVE","PRINT_BLOCK","FULLSCREEN_LEAVE","DEVTOOLS"]}
```

동일한 검증을 수행평가 틀 POST·PUT, 기존 문항/설정 PUT에 적용합니다. 틀 GET, 문항/설정 GET, 반 스냅샷, 학생 workspace에도 그대로 반환합니다.

- `POST /api/teacher/assessments`, `PUT /api/teacher/assessments/{id}`
- `PUT/GET /api/teacher/evaluations/{id}/questions` 및 `/configuration`
- `GET /api/teacher/assessments/{id}`
- `GET /api/student/evaluations/{id}/workspace` 및 `/api/evaluations/{id}/questions`

기존 템플릿 수정 정책(대기 반만 반영·시작된 반은 설정 보존)과 설정 변경 잠금은 유지합니다. 위 JSON은 antiCheat 필드 예시이며 문항·틀 저장 시 해당 API의 다른 필수 필드도 함께 전송해야 합니다.

## 학생 이벤트

`POST /api/evaluations/{id}/events`, `X-Participant-Token` 필수. 기존 `/student/evaluations/{id}/events` 및 양쪽 `/learning-events` 별칭도 같습니다.

```json
{"type":"FULLSCREEN_EXIT","occurredAt":"2026-11-06T16:00:00Z","questionId":"실제 문항 ID"}
```

```json
{"type":"DEVTOOLS_OPEN","occurredAt":"2026-11-06T16:00:00Z"}
```

questionId는 선택이며 occurredAt은 실제 발생 시각을 ISO8601로 전송합니다. 성공201 `Event`, `details.warning=true`, `details.clientReported=true`입니다. 기존 시간 범위 검증(미래5분·과거24시간), 본인 참가자/문항 확인, 평가 시작 후 제출 전 제약도 유지합니다.

FULLSCREEN_EXIT/FULLSCREEN_LEAVE/DEVTOOLS_OPEN 세 이름은 기존7개 이벤트와 **참가자별 합산60건/분**입니다. 경로 별칭을 바꿔도 같은 제한이며61번째 요청은429 `EVENT_RATE_LIMITED`, 기록·알림 추가 없이 실패합니다. 기존 PASTE·AI_ANSWER_COPY는 여전히 경고가 아닙니다.

## 집계·알림·점수

- 교사 학생 목록·제출 목록의 warningCount와 활동 조회의 warningsTotal/warnings에 포함합니다.
- `GET /api/teacher/evaluations/{id}/students/{participantId}/activity`의 warnings에 원래 type·신고 시각·수신 시각을 반환합니다. 최근200건 제한과 잘림 여부 표시는 기존과 같습니다.
- 첫 경고가 FULLSCREEN_EXIT 또는 DEVTOOLS_OPEN이어도 소유 교사·현재 공유 편집자에게 기존 경고 알림을 보냅니다. 학생별 기존 경고 중복 방지 키를 공유하므로 두 이벤트를 각각 처음 보냈다고 새 알림이 반복 생성되지는 않습니다. 개인 알림 설정도 유지합니다.
- 경고 신고는 부정행위 확정이 아닙니다. 점수 생성·수정·자동 감점은 수행하지 않습니다. 기존 과정 평가에서도 WARNING은 참고 자료이며 그것만으로 역량 점수를 정하지 않습니다.

서버는 브라우저 전체화면·개발자 도구 상태를 직접 감지하거나 진위를 인증하지 않습니다. 설정은 클라이언트 감지 동작을 위한 값이며 이벤트 수신을 설정 ON 여부로 새롭게 차단하지 않습니다. 이번 변경은 백엔드만 포함합니다. DB 마이그레이션과 새 URL은 필요하지 않습니다.


## 프런트 호환 이름

설정 FULLSCREEN도 수용하되 FULLSCREEN_LEAVE로 정규화해 반환합니다. 두 이름을 같이 보내면 중복400입니다. 이벤트 FULLSCREEN_LEAVE도 FULLSCREEN_EXIT와 같은 경고로 수용하고 원래 이름을 보존합니다. 호환 이름 포함10개 클라이언트 이벤트 전체가60건/분을 공유합니다.
