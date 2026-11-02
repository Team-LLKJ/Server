# 수행평가 공유 및 반 이름 변경 API

교사 로그인 Bearer 토큰을 사용합니다. 수행평가 소유자는 OWNER, 공유받은 교사는 EDITOR입니다.

| 메서드 | 경로 | 요청 / 응답 |
|---|---|---|
| POST | `/api/teacher/assessments/{id}/collaborators` | `{ "email": "teacher@example.com" }` → 201, teacherId/name/email/sharedAt |
| GET | `/api/teacher/assessments/{id}/collaborators` | 공유 교사 목록 |
| DELETE | `/api/teacher/assessments/{id}/collaborators/{teacherId}` | 204 |
| GET | `/api/teacher/assessments?scope=mine` | 본인 소유 목록(기본값) |
| GET | `/api/teacher/assessments?scope=shared` | 공유받은 목록 |
| PATCH | `/api/teacher/assessments/{id}/classes/{classId}` | `{ "className": "2학년 1반" }` → 변경된 ClassView |

공유 관리와 수행평가 틀 삭제는 OWNER만 가능합니다. EDITOR는 틀 조회·수정, 반 생성·시작 전 삭제·시작·종료·이름 변경, 기존 평가의 참여자·답안·대화·리포트 조회 및 채점·확정을 할 수 있습니다. 시작한 반의 공통 문항·정책·루브릭 스냅샷은 그대로 유지됩니다. 공유 해제 후 해당 평가의 교사 API와 알림에 접근할 수 없습니다.

목록/상세에 `owner: {teacherId, name}`, `role: OWNER|EDITOR`가 추가됩니다. 목록에는 `aiPolicy`, 일반 텍스트 200자 이내 `guidelinesSummary`, `scope`도 제공됩니다. 기존 페이지네이션은 동일합니다.

반 이름은 정규화 후 1~30자이며 같은 틀 안에서 중복될 수 없습니다. 시작 전·진행 중·종료 모두 이름 변경이 가능하며 PIN·종료 시각·문항·답안은 바뀌지 않습니다.

공유받은 교사에게 ASSESSMENT_SHARED 알림이 생성됩니다. 평가 운영 알림과 최종 채점 확정(GRADING_CONFIRMED) 알림은 소유자와 현재 공유 교사에게 전달됩니다. 운영 알림은 개인 알림 설정을 따르며 공유 권한 알림은 항상 생성됩니다. 반복 확정은 중복 알림을 만들지 않습니다.

오류: `TEACHER_NOT_FOUND` 404, `CANNOT_SHARE_WITH_SELF` 409, `COLLABORATOR_ALREADY_EXISTS` 409, `COLLABORATOR_NOT_FOUND` 404, `ASSESSMENT_OWNER_REQUIRED` 403, `INVALID_SCOPE` 400. 접근 권한이 없는 평가는 기존 정책에 따라 404입니다.
