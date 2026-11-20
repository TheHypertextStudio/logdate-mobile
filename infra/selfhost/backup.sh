#!/usr/bin/env bash
# Writes a consistent copy of the database and the stored media to BACKUP_DIR
# (default ./backups), and keeps the newest KEEP (default 7). Run it daily from cron.
# Everything it copies is already encrypted by the server or the apps, except the database's
# metadata; keep the backups somewhere only you can read.
set -euo pipefail
umask 077
cd "$(dirname "$0")"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP="${KEEP:-7}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$BACKUP_DIR"

docker compose exec -T postgres pg_dump -U logdate -d logdate --format=custom >"$BACKUP_DIR/db-$stamp.dump"
docker compose run --rm --no-deps -T -v "$(cd "$BACKUP_DIR" && pwd):/backup" blob-permissions \
    sh -c "umask 077 && tar -C /var/lib/logdate -czf /backup/media-$stamp.tar.gz blobs"

ls -1t "$BACKUP_DIR"/db-*.dump | tail -n +"$((KEEP + 1))" | xargs -r rm --
ls -1t "$BACKUP_DIR"/media-*.tar.gz | tail -n +"$((KEEP + 1))" | xargs -r rm --
echo "Backed up to $BACKUP_DIR (db-$stamp.dump, media-$stamp.tar.gz)."
