# Docker와 GitHub Actions 배포

## 자동화 흐름

PR 또는 기능 브랜치 변경 → Java17 Maven verify → Docker 빌드 → 실제 컨테이너 readiness 점검.
`develop`의 push 또는 workflow_dispatch만 검증된 이미지를 GHCR에 저장하고 production 환경에 배포합니다. PR은 배포하지 않습니다. main 브랜치 내용은 이 설정으로 변경되지 않습니다.

실제 배포에는 EC2 생성·서버 초기화와 아래 GitHub 설정이 먼저 필요합니다. 파일을 커밋하는 것만으로 서버나 유료 리소스를 생성하지 않습니다.

## 서버 준비

Ubuntu24.04 x86_64, Docker Engine 및 Compose v2, Nginx가 필요합니다. 배포 사용자가 Docker를 실행하고 `/opt/lit`에 파일을 쓸 수 있도록 구성합니다. Docker 그룹은 호스트 관리 권한에 준하므로 전용 배포 계정을 사용합니다.

1. `/opt/lit/app.env`에 infra/app.env.example을 복사하고 실제 값을 서버에서 입력한 뒤 권한을600으로 설정합니다. 키를 Git에 넣지 않습니다.
2. 처음 서버에서는 GitHub 사용자와 read:packages 권한 토큰으로 `docker login ghcr.io`를 수행합니다. 비공개 패키지를 읽을 수 있어야 합니다. Actions 저장소가 패키지를 게시할 권한도 필요합니다.
3. Nginx 설정은 infra/nginx.conf.example을 사용합니다. 도메인 없이 공인IP로 먼저 점검할 때 server_name은 해당 공인IP 또는 `_`로 변경하고 기본 사이트와 충돌하지 않게 설정합니다.
4. 컨테이너는127.0.0.1:5174에만 바인딩합니다. 외부 연결은 Nginx80/443을 통합니다. SSH22는 관리자·배포 러너가 접근 가능한 허용 소스로 제한합니다. GitHub 호스팅 러너 IP는 고정되지 않으므로 고정 송신IP 러너 또는 별도의 접근 구성을 확정해야 합니다. 관리자 '내IP'만 허용한 상태에서는 호스팅 러너 SSH가 연결되지 않을 수 있습니다.
5. `LIT_JOIN_BASE_URL`은 프런트 참여 주소입니다. 다른 출처 프런트에서 API를 직접 호출할 경우 `LIT_CORS_ALLOWED_ORIGINS`에 정확한 프런트 origin을 입력합니다. 쉼표로 복수 origin을 지정할 수 있으며 와일드카드는 허용하지 않습니다. HTTPS 프런트에서 HTTP API를 직접 호출하면 브라우저 혼합 콘텐츠로 차단되므로 실제 공개 연결 전 HTTPS 주소 또는 같은 출처 프록시를 구성합니다.

새 서버의 H2 DB는 로컬 개발 DB와 별개입니다. 테스트 계정이 자동 복사되지 않습니다. 최초 교사 가입에는 서버의 LIT_BOOTSTRAP_TEACHER_INVITE와 LIT_BOOTSTRAP_SCHOOL을 설정한 뒤 실제 가입 API를 사용합니다.

## GitHub production 환경

Settings → Environments → production을 생성하고 배포 브랜치를 develop으로 제한합니다. 필요하면 승인자를 설정합니다.

| Environment secret | 값 |
|---|---|
| DEPLOY_HOST | EC2 공인IP 또는 호스트 |
| DEPLOY_USER | 서버 배포 사용자 |
| DEPLOY_SSH_KEY | 전용 배포 SSH 개인키 |
| DEPLOY_KNOWN_HOSTS | 검증된 서버 SSH 호스트 키의 known_hosts 행 |

Environment variable DEPLOY_SSH_PORT는 생략 시22입니다. 호스트 키는 EC2의 신뢰할 수 있는 콘솔에서 확인하며 단순히 검증 없이 ssh-keyscan 출력을 믿지 않습니다. Gemini 키는 서버 app.env에 두며 GitHub 워크플로에는 전달하지 않습니다.

## 데이터·건강 상태·실패 처리

- readiness: GET /actuator/health/readiness. DB 연결을 포함하며 상세정보는 공개하지 않습니다.
- 단일 인스턴스 H2 파일을 Docker 볼륨 lit-data에 보존합니다. 여러 컨테이너가 같은 DB 파일을 열지 않습니다.
- Docker 이미지는 커밋 태그로 게시하지만 배포에는 불변 sha256 digest를 사용합니다.
- 교체 전 기존 앱을 중지하고 DB 볼륨을 /opt/lit/backups에 보관합니다. 그 후 새 앱의 healthy 상태를 기다립니다. H2 때문에 교체·백업 중 짧은 중단이 있습니다.
- 신규 앱 실패 시 이전 이미지로 복구를 시도하고 워크플로는 실패합니다. DB 스키마·데이터는 자동으로 되돌리지 않습니다. 호환되지 않는 마이그레이션은 별도 복구 계획이 필요합니다.
- docker compose down -v나 lit-data 볼륨 삭제는 하지 않습니다. 서버 디스크 백업은 서버 삭제를 견디지 못하므로 실제 운영 전 외부 백업과 보존 기간을 설정합니다.

컨테이너 점검에는 LIT_AI_PROVIDER=stub을 사용하며 유료 AI 호출을 하지 않습니다. CI용 실제 Gemini 키는 필요하지 않습니다.

## Git 규칙

기능 브랜치는 feature/#이슈번호-내용, 오류 수정은 fix/#이슈번호-내용을 사용합니다. 커밋 메시지는 feat : 내용 또는 fix : 내용으로 작성합니다. 이 브랜치는 공통 기반8ddeb27에서 CI/CD 변경만 분리했으며 리포트·PDF와 Gemini 재시도 수정은 각각 별도 브랜치입니다. develop의 현재 기반 코드가 비어 있으므로 공통 기반 변경이 먼저 통합되어야 합니다.
