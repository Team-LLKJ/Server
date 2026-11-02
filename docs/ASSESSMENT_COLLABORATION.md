# 수행평가 공유 및 반 이름 변경 API

교사 로그인 Bearer 토큰을 사용합니다. 수행평가 소유자는 OWNER, 공유받은 교사는 EDITOR입니다.

| 메서드 | 경로 | 요청 / 응답 |
|---|---|---|
| POST | `/api/teacher/assessments/{id}/collaborators` | `{ "email": "teacher@example.com", "role": "EDITOR" }` → 201, userId/teacherId/name/email/role/sharedAt |
| GET | `/api/teacher/assessments/{id}/collaborators` | 공유 교사 목록 |
| DELETE | `/api/teacher/assessments/{id}/collaborators/{teacherId}` | 204 |
| GET | `/api/teacher/assessments?scope=mine` | 본인 소유 목록(기본값) |
| GET | `/api/teacher/assessments?scope=shared` | 공유받은 목록 |
| GET | `/api/teacher/assessments?scope=all` | 본인 소유와 공유받은 목록의 합집합 |
| PATCH | `/api/teacher/assessments/{id}/classes/{classId}` | `{ "className": "2학년 1반" }` → 변경된 ClassView |

공유 추가·타인의 공유 해제와 수행평가 틀 삭제는 OWNER만 가능합니다. 공유 목록은 OWNER와 EDITOR가 조회할 수 있고, EDITOR는 본인의 userId로 DELETE하여 공유에서 나갈 수 있습니다. EDITOR는 틀 조회·수정, 반 생성·시작 전 삭제·시작·종료·이름 변경, 기존 평가의 참여자·답안·대화·리포트 조회 및 채점·확정을 할 수 있습니다. 시작한 반의 공통 문항·정책·루브릭 스냅샷은 그대로 유지됩니다. 공유 해제 후 해당 평가의 교사 API와 알림에 접근할 수 없습니다.

목록/상세에 `owner: {teacherId, name}`, `role: OWNER|EDITOR`, `myRole: OWNER|EDITOR`, `collaboratorCount`, `shared`가 추가됩니다. 목록에는 `aiPolicy`, 일반 텍스트 200자 이내 `guidelinesSummary`, `scope`도 제공됩니다. 기존 페이지네이션은 동일합니다.

반 이름은 정규화 후 1~30자이며 같은 틀 안에서 중복될 수 없습니다. 시작 전·진행 중·종료 모두 이름 변경이 가능하며 PIN·종료 시각·문항·답안은 바뀌지 않습니다.

공유받은 교사에게 ASSESSMENT_SHARED 알림이 생성됩니다. 평가 운영 알림과 최종 채점 확정(GRADING_CONFIRMED) 알림은 소유자와 현재 공유 교사에게 전달됩니다. 운영 알림은 개인 알림 설정을 따르며 공유 권한 알림은 항상 생성됩니다. 반복 확정은 중복 알림을 만들지 않습니다.

오류: `TEACHER_NOT_FOUND` 404, `CANNOT_SHARE_WITH_SELF` 409, `COLLABORATOR_ALREADY_EXISTS` 409, `COLLABORATOR_NOT_FOUND` 404, `ASSESSMENT_OWNER_REQUIRED` 403, `INVALID_SCOPE` 400, `INVALID_COLLABORATOR_ROLE` 400, `SHARE_TARGET_NOT_TEACHER` 404, `SHARE_TARGET_INACTIVE` 404, `ASSESSMENT_REVISION_CONFLICT` 409. 접근 권한이 없는 평가는 기존 정책에 따라 404입니다.

## 공동 편집 시 충돌 검사

`PUT /api/teacher/assessments/{id}`의 기존 전체 요청 본문에 마지막 조회에서 받은 `revision`을 추가합니다. 서버는 부모 평가 잠금 안에서 현재 revision과 비교하고, 다르면 409 `ASSESSMENT_REVISION_CONFLICT`를 반환합니다. 충돌 시 평가와 시작 전 반의 스냅샷 모두 변경하지 않습니다. 저장 성공 시 revision이 1 증가합니다. 프런트는 충돌 시 최신 GET 결과를 보여주고 다시 수정하도록 안내해야 합니다.

기존 클라이언트 호환을 위해 revision 생략 요청은 계속 허용합니다. 충돌 방지가 필요한 모든 클라이언트는 반드시 revision을 보내야 합니다. POST 생성에서는 revision을 사용하지 않습니다.

공유 role은 EDITOR만 허용하며 생략하면 EDITOR입니다. OWNER 지정은 허용하지 않습니다. OWNER는 collaborators 배열에 포함되지 않고 응답 owner 필드로 별도 표시됩니다. collaboratorCount는 활성 교사 EDITOR 수이며 shared는 해당 값이 0보다 클 때 true입니다. 기존 owner 객체와 role 필드는 유지하며, 소유자 이름은 owner.name으로 읽습니다.

공유 추가 API가 입력 이메일을 정규화하여 교사 계정을 직접 조회하므로 별도 계정 검색 API는 추가하지 않습니다. 없는 이메일(TEACHER_NOT_FOUND), 학생 계정(SHARE_TARGET_NOT_TEACHER), 비활성 교사(SHARE_TARGET_INACTIVE), 본인(CANNOT_SHARE_WITH_SELF), 중복(COLLABORATOR_ALREADY_EXISTS)을 구분합니다.

기존 EDITOR의 PIN·QR·참여자·채점·리포트 권한과 ASSESSMENT_SHARED 알림은 유지됩니다. 시작된 반의 문항·정책은 틀 수정으로 덮어쓰지 않습니다.
