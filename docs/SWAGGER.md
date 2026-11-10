# Swagger UI 및 OpenAPI

springdoc-openapi-starter-webmvc-ui 2.10.0을 사용해 Spring MVC 컨트롤러·검증 DTO에서 API 문서를 자동 생성합니다. Spring Boot 3.5 호환 범위는 [공식 문서](https://springdoc.org/v2/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)를 참고합니다.

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/docs` | 없음 | Swagger UI로 302 리다이렉트 |
| GET | `/api/swagger-ui/index.html` | 없음 | Swagger UI HTML |
| GET | `/api/openapi` | 없음 | OpenAPI JSON(경로·메서드·스키마·보안 정의) |
| GET | `/api/openapi/swagger-config` | 없음 | Swagger UI 설정 JSON |

로컬: `http://localhost:5174/api/docs`. 운영: 배포 후 `https://3.25.143.224/api/docs`. 문서·UI 자산·스키마를 모두 `/api` 아래에서 제공하므로 Nginx가 `/api/`를 백엔드로 프록시하면 됩니다. `/swagger-ui/index.html` 또는 `/v3/api-docs`는 이 프로젝트의 문서 경로가 아닙니다. 해당 경로가 프런트 HTML을 반환하더라도 정상 Swagger 응답으로 판단하지 않습니다.

## API 실행

1. `/api/auth/login`으로 로그인합니다.
2. Swagger 상단 Authorize에서 TeacherAccessToken에 accessToken 원문만 입력합니다. Bearer 접두사는 자동으로 붙습니다. Refresh Token을 넣지 않습니다.
3. 학생 API는 먼저 `/api/participation/verify` 및 `/api/participation/join`으로 참가합니다. ParticipantToken에 참가 응답의 participantToken을 넣습니다.
4. 각 경로에 표시된 인증 유형으로 실행합니다. 공개 API는 보안 요구사항이 없고 교사·학생 인증은 서로 대체되지 않습니다.

교사 토큰은 HTTP Bearer(opaque, JWT 아님), 학생 토큰은 API Key 헤더 X-Participant-Token으로 문서화합니다. 인증 헤더 입력란은 중복 표시하지 않습니다. 토큰을 문서 예시·소스에 넣지 않으며 persistAuthorization=false로 새로고침 후 자동 복원하지 않습니다.

서버 주소는 `/` 상대 경로로 지정해 문서를 보고 있는 같은 origin에 요청합니다. 로컬 문서를 열고 운영 데이터를 의도치 않게 수정하도록 운영 IP를 고정하지 않습니다. 운영에서 Try it out으로 POST/PUT/PATCH/DELETE를 실행하면 실제 데이터가 변경됩니다.

문서 조회 허용은 `/api/docs`, `/api/swagger-ui/**`, `/api/openapi`, `/api/openapi/**`, `/api/openapi.yaml`에 한정됩니다. 실제 교사·학생 API의 인증·소유권 검사는 기존대로 유지합니다. API 명세 예외·업무 규칙은 docs 및 Postman도 함께 참고하세요. Object로 선언된 기존 응답은 자동 문서에서 세부 타입이 제한될 수 있습니다.

문서 노출을 끄려면 Spring 설정 `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`를 사용합니다. 로그인·PIN 토큰 정책을 변경할 필요는 없습니다.
