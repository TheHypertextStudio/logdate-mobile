#!/usr/bin/env bash
#
# Deterministic, idempotent Android signing keystores for LogDate.
#
# Creating a release keystore by hand is unrepeatable: the flags differ between
# runs, the derived values get transcribed by hand into three different places,
# and losing the passphrase means a Play upload-key reset. This script owns the
# whole lifecycle instead — it creates the keystore once, refuses to destroy it
# afterwards, and derives every downstream value (Digital Asset Links
# fingerprint, WebAuthn apk-key-hash origin, Terraform block, CI secrets) from
# the certificate rather than asking anyone to keep them in sync.
#
# Running it twice is a no-op that reprints the same values.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SIGNING_DIR="${LOGDATE_SIGNING_DIR:-$HOME/.logdate-signing}"
PACKAGE_NAME="${LOGDATE_PACKAGE_NAME:-studio.hypertext.logdate}"
KEY_ALGORITHM="RSA"
KEY_SIZE=4096
VALIDITY_DAYS=10000

ENVIRONMENT=""
KEYSTORE=""
KEY_ALIAS=""
ENV_FILE=""
PLAY_FINGERPRINT=""
EXTRA_FINGERPRINTS=()
ASSETLINKS_OUT=""
PUSH_GITHUB="false"
GITHUB_REPO="${LOGDATE_GITHUB_REPO:-TheHypertextStudio/logdate-mobile}"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_phase() { printf '\n%b==> %s%b\n' "${CYAN}${BOLD}" "$1" "$NC"; }
log_info() { printf '%b[info]%b %s\n' "$GREEN" "$NC" "$1"; }
log_warn() { printf '%b[warn]%b %s\n' "$YELLOW" "$NC" "$1"; }
log_error() { printf '%b[error]%b %s\n' "$RED" "$NC" "$1" >&2; }

die() {
  log_error "$1"
  exit 1
}

print_usage() {
  cat << 'EOF'
create-signing-keystore.sh

Create (once) and describe an Android release keystore, then emit every value
derived from it. Idempotent: an existing keystore is reused, never replaced.

Usage:
  ./scripts/create-signing-keystore.sh --environment production [options]

Options:
  --environment ENV        production | staging   (required)
  --signing-dir DIR        Keystore home (default: $LOGDATE_SIGNING_DIR or
                           ~/.logdate-signing)
  --keystore PATH          Override the keystore path
  --alias NAME             Override the key alias
  --env-file PATH          Override the credentials file path
  --package-name NAME      Android application id
                           (default: studio.hypertext.logdate)
  --play-fingerprint FP    Colon-hex SHA-256 of the Play app-signing
                           certificate, from Play Console -> Setup -> App
                           integrity. Supplying it completes the Terraform
                           block and the Digital Asset Links file. Its
                           apk-key-hash origin is DERIVED from this digest --
                           never enter that separately.
  --extra-fingerprint FP   Additional VERIFIED colon-hex SHA-256 certificate to
                           list in assetlinks.json (repeatable). Use for the
                           debug certificate developers sign local builds with.
                           Never list a certificate you have not verified.
  --write-assetlinks PATH  Write a complete assetlinks.json to PATH
  --push-github-secrets    Upload the signing secrets to GitHub Actions with
                           `gh secret set`
  --github-repo SLUG       Repository for --push-github-secrets
  --help, -h               Show this help

Outputs (all derived from the certificate, none hand-entered):
  * SHA-256 fingerprint, colon-hex     -> Digital Asset Links, Play Console
  * apk-key-hash origin, base64url     -> WebAuthn allowed origins
  * android_signing_certificates block -> infra/terraform/*.tfvars
  * LOGDATE_RELEASE_* secrets          -> GitHub Actions

Examples:
  ./scripts/create-signing-keystore.sh --environment production
  ./scripts/create-signing-keystore.sh --environment production \
      --play-fingerprint AA:BB:...:FF \
      --write-assetlinks ../logdate-web/public/.well-known/assetlinks.json
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment)
      ENVIRONMENT="${2:-}"
      shift 2
      ;;
    --signing-dir)
      SIGNING_DIR="${2:-}"
      shift 2
      ;;
    --keystore)
      KEYSTORE="${2:-}"
      shift 2
      ;;
    --alias)
      KEY_ALIAS="${2:-}"
      shift 2
      ;;
    --env-file)
      ENV_FILE="${2:-}"
      shift 2
      ;;
    --package-name)
      PACKAGE_NAME="${2:-}"
      shift 2
      ;;
    --play-fingerprint)
      PLAY_FINGERPRINT="${2:-}"
      shift 2
      ;;
    --extra-fingerprint)
      EXTRA_FINGERPRINTS+=("${2:-}")
      shift 2
      ;;
    --write-assetlinks)
      ASSETLINKS_OUT="${2:-}"
      shift 2
      ;;
    --push-github-secrets)
      PUSH_GITHUB="true"
      shift
      ;;
    --github-repo)
      GITHUB_REPO="${2:-}"
      shift 2
      ;;
    --help | -h)
      print_usage
      exit 0
      ;;
    *)
      print_usage
      die "Unknown argument: $1"
      ;;
  esac
done

case "$ENVIRONMENT" in
  production | staging) ;;
  "")
    print_usage
    die "--environment is required."
    ;;
  *) die "--environment must be 'production' or 'staging' (got '$ENVIRONMENT')." ;;
esac

command -v keytool > /dev/null || die "keytool not found. Install a JDK."
command -v openssl > /dev/null || die "openssl not found."

KEYSTORE="${KEYSTORE:-$SIGNING_DIR/${ENVIRONMENT}-upload.jks}"
ENV_FILE="${ENV_FILE:-$SIGNING_DIR/${ENVIRONMENT}-upload.env}"
KEY_ALIAS="${KEY_ALIAS:-logdate-${ENVIRONMENT}-upload}"

# A keystore without its credentials file is unusable and unrecoverable. Fail
# loudly rather than silently generating a second, conflicting key.
if [[ -f "$KEYSTORE" && ! -f "$ENV_FILE" ]]; then
  die "Keystore $KEYSTORE exists but $ENV_FILE does not. The passphrase cannot be recovered; restore the credentials file from backup."
fi

if [[ -f "$KEYSTORE" ]]; then
  log_phase "Reusing existing $ENVIRONMENT keystore"
  log_info "$KEYSTORE"
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  KEY_ALIAS="${LOGDATE_RELEASE_KEY_ALIAS:-$KEY_ALIAS}"
  STORE_PASSWORD="${LOGDATE_RELEASE_STORE_PASSWORD:?missing in $ENV_FILE}"
  KEY_PASSWORD="${LOGDATE_RELEASE_KEY_PASSWORD:-$STORE_PASSWORD}"

  # Gradle signs with whatever LOGDATE_RELEASE_STORE_FILE names, while everything
  # printed below is derived from $KEYSTORE. If the two disagree -- because the
  # keystore moved, or --keystore pointed elsewhere -- the published fingerprint
  # would describe a certificate the build never used, and passkey enrolment would
  # fail on device. Keep the recorded path pointing at the keystore actually read.
  if [[ "${LOGDATE_RELEASE_STORE_FILE:-}" != "$KEYSTORE" ]]; then
    log_warn "Updating LOGDATE_RELEASE_STORE_FILE in $ENV_FILE to $KEYSTORE"
    ENV_TMP="$(mktemp)"
    chmod 600 "$ENV_TMP"
    sed "s#^LOGDATE_RELEASE_STORE_FILE=.*#LOGDATE_RELEASE_STORE_FILE=$KEYSTORE#" \
      "$ENV_FILE" > "$ENV_TMP"
    cat "$ENV_TMP" > "$ENV_FILE"
    rm -f "$ENV_TMP"
    LOGDATE_RELEASE_STORE_FILE="$KEYSTORE"
  fi
else
  log_phase "Creating $ENVIRONMENT keystore"
  mkdir -p "$SIGNING_DIR"
  chmod 700 "$SIGNING_DIR"

  # Hex passphrases: no shell-special characters, so they survive being
  # passed through Gradle, CI, and keytool without quoting hazards.
  STORE_PASSWORD="$(openssl rand -hex 24)"
  KEY_PASSWORD="$STORE_PASSWORD"

  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -alias "$KEY_ALIAS" \
    -keyalg "$KEY_ALGORITHM" \
    -keysize "$KEY_SIZE" \
    -validity "$VALIDITY_DAYS" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=LogDate, OU=${ENVIRONMENT}, O=Hypertext Studio, C=US" \
    > /dev/null 2>&1 || die "keytool failed to create $KEYSTORE"

  chmod 600 "$KEYSTORE"

  umask 077
  cat > "$ENV_FILE" << ENV
# LogDate ${ENVIRONMENT} upload keystore.
# Generated by scripts/create-signing-keystore.sh. Do not edit by hand.
# BACK THIS DIRECTORY UP. Losing it means a Play upload-key reset.
LOGDATE_RELEASE_STORE_FILE=$KEYSTORE
LOGDATE_RELEASE_STORE_PASSWORD=$STORE_PASSWORD
LOGDATE_RELEASE_KEY_ALIAS=$KEY_ALIAS
LOGDATE_RELEASE_KEY_PASSWORD=$KEY_PASSWORD
ENV
  chmod 600 "$ENV_FILE"
  log_info "Created $KEYSTORE (RSA ${KEY_SIZE}, ${VALIDITY_DAYS} days)"
  log_info "Credentials written to $ENV_FILE"
fi

# Derive everything from the certificate itself. The colon-hex fingerprint and
# the base64url apk-key-hash origin are two encodings of one digest, so they can
# never disagree.
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

keytool -exportcert \
  -keystore "$KEYSTORE" \
  -alias "$KEY_ALIAS" \
  -storepass "$STORE_PASSWORD" \
  > "$WORKDIR/cert.der" 2> /dev/null \
  || die "Could not export alias '$KEY_ALIAS' from $KEYSTORE."

openssl dgst -sha256 -binary "$WORKDIR/cert.der" > "$WORKDIR/digest.bin"

digest_to_colon_hex() {
  xxd -p -c 1 "$1" | tr 'a-f' 'A-F' | paste -sd: -
}

digest_to_apk_key_hash() {
  openssl base64 -A -in "$1" | tr '+/' '-_' | tr -d '='
}

colon_hex_to_apk_key_hash() {
  printf '%s' "$1" | tr -d ':' | tr 'A-F' 'a-f' | xxd -r -p \
    | openssl base64 -A | tr '+/' '-_' | tr -d '='
}

UPLOAD_FINGERPRINT="$(digest_to_colon_hex "$WORKDIR/digest.bin")"
UPLOAD_ORIGIN="android:apk-key-hash:$(digest_to_apk_key_hash "$WORKDIR/digest.bin")"

normalize_fingerprint() {
  local flag="$1" value="$2"
  value="$(printf '%s' "$value" | tr -d '[:space:]' | tr 'a-f' 'A-F')"
  [[ "$value" =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]] \
    || die "$flag must be 32 colon-separated hex bytes (got '$value')."
  printf '%s' "$value"
}

if [[ -n "$PLAY_FINGERPRINT" ]]; then
  PLAY_FINGERPRINT="$(normalize_fingerprint --play-fingerprint "$PLAY_FINGERPRINT")"
  [[ "$PLAY_FINGERPRINT" != "$UPLOAD_FINGERPRINT" ]] \
    || die "--play-fingerprint is identical to the upload certificate. Play app signing issues a distinct certificate."
  PLAY_ORIGIN="android:apk-key-hash:$(colon_hex_to_apk_key_hash "$PLAY_FINGERPRINT")"
fi

NORMALIZED_EXTRAS=()
for extra in ${EXTRA_FINGERPRINTS[@]+"${EXTRA_FINGERPRINTS[@]}"}; do
  extra="$(normalize_fingerprint --extra-fingerprint "$extra")"
  [[ "$extra" != "$UPLOAD_FINGERPRINT" && "$extra" != "${PLAY_FINGERPRINT:-}" ]] \
    || die "--extra-fingerprint duplicates a certificate this script already emits."
  NORMALIZED_EXTRAS+=("$extra")
done

log_phase "Certificate"
printf '  environment        %s\n' "$ENVIRONMENT"
printf '  keystore           %s\n' "$KEYSTORE"
printf '  alias              %s\n' "$KEY_ALIAS"
printf '  SHA-256            %s\n' "$UPLOAD_FINGERPRINT"
printf '  apk-key-hash       %s\n' "$UPLOAD_ORIGIN"

log_phase "Terraform (infra/terraform/${ENVIRONMENT}.tfvars)"
if [[ -n "$PLAY_FINGERPRINT" ]]; then
  cat << TFVARS
android_signing_certificates = {
  upload = {
    fingerprint         = "$UPLOAD_FINGERPRINT"
    apk_key_hash_origin = "$UPLOAD_ORIGIN"
  }
  play_app_signing = {
    fingerprint         = "$PLAY_FINGERPRINT"
    apk_key_hash_origin = "$PLAY_ORIGIN"
  }
}
TFVARS
else
  cat << TFVARS
# Incomplete: re-run with --play-fingerprint once the app exists in Play Console
# (Setup -> App integrity). The contract requires both certificates.
android_signing_certificates = {
  upload = {
    fingerprint         = "$UPLOAD_FINGERPRINT"
    apk_key_hash_origin = "$UPLOAD_ORIGIN"
  }
}
TFVARS
fi

build_assetlinks() {
  local fingerprints="        \"$UPLOAD_FINGERPRINT\""
  if [[ -n "$PLAY_FINGERPRINT" ]]; then
    fingerprints="$fingerprints,
        \"$PLAY_FINGERPRINT\""
  fi
  local extra
  for extra in ${NORMALIZED_EXTRAS[@]+"${NORMALIZED_EXTRAS[@]}"}; do
    fingerprints="$fingerprints,
        \"$extra\""
  done
  cat << JSON
[
  {
    "relation": [
      "delegate_permission/common.get_login_creds",
      "delegate_permission/common.handle_all_urls"
    ],
    "target": {
      "namespace": "android_app",
      "package_name": "$PACKAGE_NAME",
      "sha256_cert_fingerprints": [
$fingerprints
      ]
    }
  }
]
JSON
}

log_phase "Digital Asset Links (https://logdate.app/.well-known/assetlinks.json)"
build_assetlinks

if [[ -n "$ASSETLINKS_OUT" ]]; then
  mkdir -p "$(dirname "$ASSETLINKS_OUT")"
  build_assetlinks > "$ASSETLINKS_OUT"
  log_info "Wrote $ASSETLINKS_OUT"
  if [[ -z "$PLAY_FINGERPRINT" ]]; then
    log_warn "Only the upload certificate is listed. Play-signed installs will fail passkey creation until you re-run with --play-fingerprint."
  fi
fi

if [[ "$PUSH_GITHUB" == "true" ]]; then
  log_phase "GitHub Actions secrets"
  command -v gh > /dev/null || die "gh not found. Install the GitHub CLI."
  [[ "$ENVIRONMENT" == "production" ]] \
    || log_warn "Pushing $ENVIRONMENT credentials to repository-level secrets."
  openssl base64 -A -in "$KEYSTORE" > "$WORKDIR/keystore.b64"
  gh secret set LOGDATE_RELEASE_STORE_BASE64 --repo "$GITHUB_REPO" < "$WORKDIR/keystore.b64"
  printf '%s' "$STORE_PASSWORD" | gh secret set LOGDATE_RELEASE_STORE_PASSWORD --repo "$GITHUB_REPO"
  printf '%s' "$KEY_ALIAS" | gh secret set LOGDATE_RELEASE_KEY_ALIAS --repo "$GITHUB_REPO"
  printf '%s' "$KEY_PASSWORD" | gh secret set LOGDATE_RELEASE_KEY_PASSWORD --repo "$GITHUB_REPO"
  log_info "Set LOGDATE_RELEASE_* on $GITHUB_REPO"
else
  log_phase "GitHub Actions secrets"
  printf '  Re-run with --push-github-secrets to upload these to %s.\n' "$GITHUB_REPO"
fi

log_phase "Local builds"
printf '  set -a; source %s; set +a\n' "$ENV_FILE"
printf '  ./gradlew :app:android-main:bundleRelease\n'
