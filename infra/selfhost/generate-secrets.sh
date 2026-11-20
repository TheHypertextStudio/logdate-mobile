#!/usr/bin/env bash
# Creates secrets.env with fresh random secrets, and logdate.env from its example, for a new
# LogDate server on this machine. It never overwrites either file: the secrets encrypt stored
# data, so replacing them makes everything already stored unreadable.
set -euo pipefail
umask 077
cd "$(dirname "$0")"

if [[ ! -f logdate.env ]]; then
    cp logdate.env.example logdate.env
    echo "Created logdate.env from logdate.env.example."
fi

if [[ -e secrets.env ]]; then
    echo "secrets.env already exists; leaving it alone." >&2
    exit 0
fi

secret() { openssl rand -base64 48 | tr -d '\n'; }

{
    echo "# Generated $(date -u +%Y-%m-%dT%H:%M:%SZ) by generate-secrets.sh."
    echo "# Keep a copy somewhere other than this machine. Losing SERVER_ENCRYPTION_KEY or"
    echo "# ATPROTO_SIGNING_KEY_KEK makes every stored journal and identity unreadable."
    database_password="$(openssl rand -hex 24)"
    echo "DATABASE_PASSWORD=$database_password"
    echo "POSTGRES_PASSWORD=$database_password"
    echo "JWT_SECRET=$(secret)"
    echo "ATPROTO_SIGNING_KEY_KEK=$(secret)"
    echo "ATPROTO_SESSION_SECRET=$(secret)"
    echo "SERVER_ENCRYPTION_KEY=$(openssl rand -base64 32 | tr -d '\n')"
    echo "SERVER_ENCRYPTION_KEY_ID=selfhost-$(date -u +%Y%m%d)"
    echo "HEALTH_INTERNAL_TOKEN=$(openssl rand -hex 32)"
    echo "# Comma-separated Google OAuth client IDs that may sign in with Google; optional."
    echo "GOOGLE_OIDC_CLIENT_IDS="
} >secrets.env

chmod 600 secrets.env logdate.env
echo "Created secrets.env. Copy it to your password manager now."
