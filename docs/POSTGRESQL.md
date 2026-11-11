# PostgreSQL 전환

LIT는 `postgres` 프로필에서 PostgreSQL을 사용합니다. 계정, 평가, 제출본, 채점 작업과 결과는 한 데이터베이스에 저장됩니다. 기존 H2 설정으로도 로컬 실행과 테스트가 가능합니다.

## 로컬 검증

PostgreSQL 17 이상의 검증용 데이터베이스와 스키마를 초기화할 권한이 있는 계정을 준비합니다. 아래 변수의 비밀번호는 환경에서 주입합니다.

```bash
export SPRING_PROFILES_ACTIVE=postgres
export DATABASE_URL=jdbc:postgresql://localhost:5432/lit
export DATABASE_USER=lit
./mvnw spring-boot:run
```

`postgres` 프로필은 `schema-postgresql.sql`을 실행합니다. 스키마 변경이 있을 때는 H2의 `schema.sql`과 PostgreSQL 스키마를 함께 검토합니다. PostgreSQL 통합 테스트에는 운영 DB가 아닌 전용 테스트 DB를 사용합니다.

```bash
export LIT_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/lit_test
export LIT_TEST_POSTGRES_USER=lit
./mvnw verify
```

테스트 계정에 비밀번호가 필요하면 `LIT_TEST_POSTGRES_PASSWORD`를 함께 설정합니다. 이 테스트는 한글 답안 저장·갱신, 제출, 분석 완료, PDF·HWP 다운로드를 확인합니다.

## 기존 데이터 이전

1. LIT를 멈춘 뒤 H2의 `lit.mv.db`를 백업합니다. 이전 도구는 백업 복사본을 읽기 전용으로 엽니다. 운영 전환 전에 복사본으로 이전과 복구를 검증합니다.
2. PostgreSQL에 LIT 전용 빈 DB를 만들고 대상 DB에서 `src/main/resources/schema-postgresql.sql`을 실행합니다. 복사 중에는 앱을 대상 DB에 연결하지 않습니다.
3. H2 파일명에서 `.mv.db`를 뺀 경로로 소스 URL을 지정합니다. PostgreSQL은 위의 `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`를 사용합니다. H2에 별도 자격 증명이 있으면 `LIT_H2_SOURCE_USER`, `LIT_H2_SOURCE_PASSWORD`를 지정합니다.

```bash
export LIT_H2_SOURCE_URL=jdbc:h2:file:/absolute/backup/lit
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/migration-classpath
java -cp "$(cat target/migration-classpath)" scripts/MigrateH2ToPostgres.java
```

대상에 데이터가 있으면 이전 도구가 중단됩니다. 외래키 순서에 맞춰 복사하고 테이블별 행 수와 자동 증가 ID의 시작값을 확인합니다. 오류가 발생하면 대상 DB의 이번 작업 전체가 롤백됩니다.

4. PostgreSQL을 사용하는 검증 서버에서 로그인, 기존 평가·제출본 조회, 신규 제출·채점, PDF·HWP 다운로드를 확인합니다. 운영 전환 시에는 쓰기를 멈춘 상태에서 최신 백업으로 다시 이전하고, 이 확인을 마친 뒤 트래픽을 엽니다.

## Docker 운영

전환 대상 서버의 `app.env`에 `SPRING_PROFILES_ACTIVE=postgres`와 PostgreSQL 접속 정보 세 가지를 설정합니다. PostgreSQL 주소는 앱 컨테이너와 배포 호스트 양쪽에서 접속할 수 있어야 합니다. 기존 H2 볼륨과 전환 전 설정 파일은 복구에 사용할 수 있도록 보관합니다.

배포 호스트에는 대상 서버 버전과 호환되는 `pg_dump`가 필요합니다. 배포 스크립트는 이전 컨테이너의 DB 설정을 읽어 PostgreSQL은 custom dump로, H2는 볼륨 압축 파일로 백업합니다. 백업용 JDBC URL은 `jdbc:postgresql://host:5432/database` 형식으로 설정하며, 선택적으로 `?sslmode=require`와 같은 `sslmode` 옵션을 지정할 수 있습니다. 자격 증명은 별도 환경 변수로 설정합니다.

전환 후 새 DB에서 쓰기를 받기 시작한 시점부터는 H2로 되돌리기 전에 새 데이터의 보존 방법을 확인해야 합니다. 운영 트래픽을 열기 전까지는 기존 이미지와 H2 접속 설정을 복원하는 방식으로 되돌릴 수 있습니다.
