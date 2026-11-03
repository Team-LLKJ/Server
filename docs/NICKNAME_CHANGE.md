# 닉네임 변경

`PATCH /api/users/me/nickname`으로 로그인한 본인의 닉네임을 변경합니다. 기존 `PATCH /api/users/me`의 `name` 요청도 같은 검증과 저장 경로를 사용합니다. 닉네임은 기존 `display_name`이며 응답은 기존 User의 `name` 필드입니다.

Bearer Access Token 필수. 요청 `{ "nickname": "새 닉네임" }`, 성공 200 User. 앞뒤 공백 제거, 공백만 입력 불가, 최대 50자(요청 길이 기준). 중복 닉네임 허용, 아이디(username)는 변경하지 않습니다. 400 INVALID_REQUEST, 미인증·폐기 세션은 401. 사용자 행을 잠근 뒤 세션을 재확인해 로그아웃·탈퇴와 경합할 때 변경하지 않습니다.
