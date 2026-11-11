#!/usr/bin/env bash
set -Eeuo pipefail
cd /opt/lit
image=${1:?Pass immutable GHCR image digest}
[[ $image =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]] || { echo 'Invalid image reference'; exit 1; }
[[ -f app.env && -f compose.yml ]] || { echo 'Missing /opt/lit/app.env or compose.yml'; exit 1; }
chmod 600 app.env
exec 9>.deploy.lock
flock -n 9 || { echo 'Another deployment is in progress'; exit 1; }
candidate=$(mktemp /opt/lit/.release.XXXXXX)
trap 'rm -f "$candidate"' EXIT
printf 'APP_IMAGE=%s\n' "$image" > "$candidate"
chmod 600 "$candidate"
previous=''
if [[ -f .release.env ]]; then
  previous=$(sed -n 's/^APP_IMAGE=//p' .release.env)
  [[ $previous =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]] || { echo 'Invalid previous release'; exit 1; }
fi
# Pull before stopping the existing app. Private GHCR login is provisioned once on this host.
docker compose -f compose.yml --env-file "$candidate" pull app
if [[ -n $previous ]]; then
  # Inspect the previous container's database settings before stopping it. app.env can already
  # point to the next database during a cutover, so it is not the source of backup settings.
  previous_container=$(docker compose -f compose.yml --env-file .release.env ps --all --quiet app)
  [[ -n $previous_container ]] || { echo 'Previous application container is missing'; exit 1; }
  previous_env=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$previous_container")
  previous_setting() {
    sed -n "s/^$1=//p" <<< "$previous_env" | head -n 1
  }
  previous_url=$(previous_setting DATABASE_URL)
  previous_profiles=$(previous_setting SPRING_PROFILES_ACTIVE)
  case "$previous_url" in
    jdbc:postgresql://*)
      [[ $previous_url =~ ^jdbc:postgresql://[a-zA-Z0-9.-]+(:[0-9]+)?/[a-zA-Z0-9_-]+(\?sslmode=(disable|allow|prefer|require|verify-ca|verify-full))?$ ]] ||
        { echo 'Use a PostgreSQL JDBC URL without credentials and with only optional sslmode'; exit 1; }
      backup_db=postgres
      command -v pg_dump >/dev/null || { echo 'pg_dump is required to back up PostgreSQL'; exit 1; }
      ;;
    jdbc:h2:file:/app/data/lit|jdbc:h2:file:./data/lit|'')
      [[ ",$previous_profiles," != *,postgres,* ]] || { echo 'Explicit PostgreSQL DATABASE_URL is required'; exit 1; }
      backup_db=h2
      ;;
    *) echo 'Unsupported database backup configuration'; exit 1 ;;
  esac

  mkdir -p backups
  chmod 700 backups
  backup_name="lit-$(date -u +%Y%m%dT%H%M%SZ)"
  docker compose -f compose.yml --env-file .release.env stop app
  backup() {
    if [[ $backup_db == postgres ]]; then
      # Supply credentials through the environment so they are not passed in command arguments.
      (
        umask 077
        PGUSER="$(previous_setting DATABASE_USER)"           PGPASSWORD="$(previous_setting DATABASE_PASSWORD)"           pg_dump --no-password --dbname="${previous_url#jdbc:}" --format=custom --file="backups/$backup_name.dump.part" &&
          mv "backups/$backup_name.dump.part" "backups/$backup_name.dump"
      )
    else
      docker run --rm --user 0 --entrypoint sh -v lit-data:/data:ro -v /opt/lit/backups:/backup         -e BACKUP_ARCHIVE="$backup_name.tar.gz" -e BACKUP_OWNER="$(id -u):$(id -g)" "$previous"         -c 'umask 077; tar -czf "/backup/$BACKUP_ARCHIVE" -C /data . && chown "$BACKUP_OWNER" "/backup/$BACKUP_ARCHIVE"'
    fi
  }
  if ! backup; then
    # Start the existing container with its original environment on backup failure.
    docker compose -f compose.yml --env-file .release.env start app
    rm -f "backups/$backup_name.dump.part"
    echo 'Backup failed; previous release restored'; exit 1
  fi
  unset previous_env
fi
if docker compose -f compose.yml --env-file "$candidate" up -d --wait --wait-timeout 150 app; then
  mv "$candidate" .release.env
  echo "Deployment healthy: $image"
else
  echo 'New release failed its health check'
  if [[ -n $previous ]]; then
    docker compose -f compose.yml --env-file .release.env up -d --wait --wait-timeout 150 app
    echo 'Previous application image restored; database is not automatically reverted'
  fi
  exit 1
fi
