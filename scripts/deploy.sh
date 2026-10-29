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
  docker compose -f compose.yml --env-file .release.env stop app
  mkdir -p backups
  chmod 700 backups
  archive="lit-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
  if ! docker run --rm --user 0 --entrypoint sh -v lit-data:/data:ro -v /opt/lit/backups:/backup \
      -e BACKUP_ARCHIVE="$archive" -e BACKUP_OWNER="$(id -u):$(id -g)" "$previous" \
      -c 'umask 077; tar -czf "/backup/$BACKUP_ARCHIVE" -C /data . && chown "$BACKUP_OWNER" "/backup/$BACKUP_ARCHIVE"'; then
    docker compose -f compose.yml --env-file .release.env up -d --wait --wait-timeout 150 app
    echo 'Backup failed; previous release restored'; exit 1
  fi
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
