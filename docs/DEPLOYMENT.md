# EC2 및 GitHub Actions 배포

## 자동화 흐름

PR·기능 브랜치 변경 → GitHub 호스팅 러너에서 Java17 Maven verify → Docker 빌드 → 실제 컨테이너 readiness 점검.
`develop`의 push 또는 workflow_dispatch만 검증된 이미지를 GHCR에 게시하고 production 환경에 배포합니다. PR은 배포하지 않습니다.

배포 작업만 비공개 저장소 전용 self-hosted 러너(lit-production)가 EC2에서 실행합니다. EC2에 Docker, Compose, Nginx와 전용 GitHub 러너 서비스를 설치해야 합니다. GitHub 러너가 외부로 접속하므로 배포를 위해 SSH를 인터넷 전체에 열 필요가 없습니다. 이 구성은 비공개 저장소의 신뢰된 협업자를 전제로 하며 공개 저장소·신뢰할 수 없는 PR용 러너로 재사용하지 않습니다.

## 서버·환경 설정

- EC2: Ubuntu24.04 x86_64, 메모리2GB 이상. 배포 사용자 ubuntu는 /opt/lit 소유자이며 Docker 실행 권한이 필요합니다.
- `/opt/lit/app.env`: infra/app.env.example을 참고해 실제 값을 서버에만 저장하고 권한을600으로 설정합니다. Gemini 키는 GitHub Secrets나 저장소에 넣지 않습니다.
- `production` GitHub Environment: 배포 브랜치를 develop으로 제한하고 variable PRODUCTION_URL에 실제 HTTPS API 주소를 지정합니다.
- 전용 SSH 개인키를 GitHub에 저장하지 않습니다. 이미지는 배포 작업의 단기 GITHUB_TOKEN(packages:read)으로 내려받고 작업 종료 시 docker logout으로 지웁니다.
- 패키지 게시 작업은 packages:write이며 OCI source 라벨을 저장소와 연결합니다. 운영 컨테이너에는 GitHub 토큰을 전달하지 않습니다.
- 운영 컨테이너 포트는127.0.0.1:5174로 제한하고 Nginx80/443을 통합니다. 실제 클라이언트 IP는 Nginx의 전달 헤더와 Tomcat의 신뢰 프록시 처리로 확인합니다.
- 프런트 개발 프록시는 VITE_API_PROXY_TARGET에 HTTPS API 주소를 사용합니다. 브라우저가 API를 직접 호출하면 LIT_CORS_ALLOWED_ORIGINS에 정확한 프런트 origin을 지정합니다(쉼표 구분, 와일드카드 금지).
- LIT_JOIN_BASE_URL은 공개 프런트의 참여 페이지입니다. 프런트를 배포하기 전에는 QR의 공개 참여 주소도 따로 확정해야 합니다.

## HTTPS

도메인 없이 Let's Encrypt의 shortlived IP 인증서와 Certbot5.4 이상 webroot 방식을 사용할 수 있습니다. 발급 전 이용약관에 동의해야 합니다. 실제 서버의 인증서는 /etc/letsencrypt에만 두고 저장소에 올리지 않습니다.

Nginx는 HTTP-01 검증 경로를 제공하고 나머지 HTTP 요청을 HTTPS로 보냅니다. lit-cert-renew.timer가 하루4회 갱신을 확인하며 성공 후 Nginx 설정 검사·재시작 없이 reload합니다. IP 인증서는 약6일 유효하므로 갱신 타이머를 유지해야 합니다. EC2 중지·시작으로 공인IP가 달라지면 인증서·PRODUCTION_URL·클라이언트 주소를 함께 변경해야 합니다.

## DB·건강 상태·실패 처리

- GET /actuator/health/readiness는 DB 연결을 확인하며 상세정보는 노출하지 않습니다.
- 단일 인스턴스 H2 DB는 Docker 볼륨 lit-data에 보관합니다. 여러 컨테이너가 같은 파일을 열지 않습니다.
- 기존 DB 이전은 원본 서버를 정상 종료한 뒤 파일을 복사하고 체크섬을 검증합니다. 맥과 EC2의 파일은 이후 서로 별개의 DB이므로 이전 후에는 EC2를 기준으로 사용합니다.
- 이미지에는 커밋 태그를 붙이지만 실제 배포에는 불변 sha256 digest를 사용합니다.
- 새 이미지 pull 성공 후 기존 앱을 정상 중지하고 DB를 /opt/lit/backups에 백업한 다음 교체합니다. H2 때문에 교체·백업 중 짧은 중단이 있습니다.
- 새 앱이 unhealthy이면 이전 이미지 복구를 시도하며 워크플로는 실패합니다. DB 스키마·데이터는 자동으로 되돌리지 않습니다. 호환되지 않는 스키마 변경은 별도 복구 계획이 필요합니다.
- docker compose down -v나 lit-data 삭제는 하지 않습니다. 서버 내 백업은 서버 삭제를 견디지 못하므로 운영 데이터는 별도 외부 백업과 보존 기간을 설정합니다.

## Git 규칙

feature/#이슈번호-내용, fix/#이슈번호-내용 브랜치와 feat : 내용, fix : 내용 커밋 형식을 사용합니다. 기능별 PR을 develop에 통합한 뒤 CI/CD PR을 마지막에 병합합니다. main은 배포 대상으로 변경하지 않습니다.
