#!/usr/bin/env bash
# Builds the server from the checked-out commit and (re)starts PostgreSQL, the server, and Caddy,
# then waits until the server reports a healthy database.
set -euo pipefail
cd "$(dirname "$0")"

[[ -f logdate.env && -f secrets.env ]] || { echo "Run ./generate-secrets.sh first." >&2; exit 1; }
if [[ -n "$(git status --porcelain -- ../../server ../../shared)" ]]; then
    echo "The server or shared sources have uncommitted changes; RELEASE_VERSION would misreport them." >&2
    exit 1
fi

# Compose reads .env for every later command too (logs, exec, backup.sh), not only this one.
echo "RELEASE_VERSION=logdate-server@$(git rev-parse HEAD)" >.env
HEALTH_INTERNAL_TOKEN="$(sed -n 's/^HEALTH_INTERNAL_TOKEN=//p' secrets.env)"

docker compose up -d --build --remove-orphans

echo "Waiting for the server to report a healthy database..."
for _ in $(seq 1 60); do
    if docker compose exec -T caddy wget -qO- \
        --header "X-Forwarded-Proto: https" \
        --header "X-LogDate-Health-Token: ${HEALTH_INTERNAL_TOKEN}" \
        http://server:8080/health 2>/dev/null | grep -q '"db_connected":true'; then
        echo "Server is up at $(git rev-parse --short HEAD)."
        exit 0
    fi
    sleep 5
done
echo "The server did not become healthy. Its log:" >&2
docker compose logs --tail=80 server >&2
exit 1
