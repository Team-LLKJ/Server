# 비밀번호 변경

`POST /api/users/me/password`로 현재 비밀번호를 확인한 후 새 비밀번호로 변경합니다. 기존 경로·응답은 유지합니다. 비밀번호 해시 갱신과 다른 기기의 Access/Refresh 세션 폐기를 한 트랜잭션으로 처리합니다.

Bearer Access Token 필수. 요청 `{ "currentPassword": "현재 비밀번호", "newPassword": "새 비밀번호8자이상" }`, 성공 200 `{ "message": "비밀번호가 변경되었습니다. 다른 기기의 로그인은 해제되었습니다." }`. 새 비밀번호 8~128자, 공백만 입력 불가. 기존 비밀번호와 동일하면 400 PASSWORD_UNCHANGED, 현재 비밀번호 불일치 시 400 INVALID_CURRENT_PASSWORD. 형식 오류 400 INVALID_REQUEST, 미인증·폐기 세션 401. 현재 세션 계열은 유지하며 다른 기기의 갱신 토큰도 폐기합니다. 실패 시 비밀번호·세션은 변경되지 않습니다. 현재 로그인 시도 제한(비밀번호 변경 경로 IP당 10회/분)을 유지합니다.
