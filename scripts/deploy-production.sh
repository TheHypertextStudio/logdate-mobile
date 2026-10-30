#!/usr/bin/env bash
#
# deploy-production.sh — Interactive turnkey production deploy for the LogDate
# Ktor server on GCP Cloud Run.
#
# Defaults to zero-flag interactive mode: run `./run deploy:production` with no
# arguments and the script walks you through every missing input. Every
# interactive prompt can be pre-answered with an env var or a CLI flag for
# unattended reruns.
#
# Phases (each idempotent, each skippable via --skip-<phase>):
#   0. Prereq validation + gcloud auth
#   1. Domain ownership pre-flight (Search Console verify URLs)
#   2. Write infra/terraform/production.tfvars (if missing or --overwrite)
#   3. terraform init + plan + apply
#   4. Populate Secret Manager (auto-generates JWT; prompts for DB/OIDC/Redis)
#   5. Build + push Docker image, deploy to Cloud Run
#   6. Publish DNS records (via Cloudflare API) or print them for manual entry
#   7. Verify /health on the service URL and each custom domain
#
# Usage (all flags optional — script prompts for missing values):
#   ./scripts/deploy-production.sh [flags]
#
# Flags:
#   --project-id <id>            GCP project (default: auto-detect)
#   --region <region>            Default: us-central1
#   --primary-domain <host>      Default: logdate.hypertext.studio
#   --secondary-domain <host>    Default: cloud.logdate.app (empty string to skip)
#   --webauthn-rp-id <host>      Default: primary domain
#   --dns-provider <prov>        cloudflare | manual  (default: prompt)
#   --overwrite                  Overwrite existing production.tfvars
#   --auto-approve               Skip terraform apply confirmation
#   --non-interactive            Fail instead of prompting for missing inputs
#   --skip-prereqs, --skip-domain-check, --skip-tfvars, --skip-terraform,
#   --skip-secrets, --skip-image, --skip-dns, --skip-verify
#   -h, --help
#
# Env vars (all optional; every one can also be set via a flag or prompt):
#   LOGDATE_GCP_PROJECT_ID       GCP project
#   CLOUDFLARE_API_TOKEN         Cloudflare API token with Zone:DNS:Edit

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TF_DIR="${REPO_ROOT}/infra/terraform"
TFVARS_PATH="${TF_DIR}/production.tfvars"
BUILD_SCRIPT="${SCRIPT_DIR}/deploy-cloud-run.sh"

PROJECT_ID="${LOGDATE_GCP_PROJECT_ID:-}"
REGION="us-central1"
PRIMARY_DOMAIN="logdate.hypertext.studio"
SECONDARY_DOMAIN="cloud.logdate.app"
WEBAUTHN_RP_ID=""
DNS_PROVIDER=""
OVERWRITE_TFVARS="false"
AUTO_APPROVE="false"
NON_INTERACTIVE="false"
SKIP_PREREQS="false"
SKIP_DOMAIN_CHECK="false"
SKIP_TFVARS="false"
SKIP_TERRAFORM="false"
SKIP_SECRETS="false"
SKIP_IMAGE="false"
SKIP_DNS="false"
SKIP_VERIFY="false"

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

# Normalize a yes/no reply — strip CR/LF/whitespace, lowercase. Defends
# against terminals that emit `\r\n` (Windows / some SSH clients) and
# against leftover stdin bytes from a previous subcommand.
normalize_reply() {
  printf '%s' "$1" | tr -d '[:space:]\r' | tr '[:upper:]' '[:lower:]'
}

reply_is_yes() {
  local norm
  norm=$(normalize_reply "$1")
  [[ "$norm" == "y" || "$norm" == "yes" ]]
}

reply_is_no() {
  local norm
  norm=$(normalize_reply "$1")
  [[ "$norm" == "n" || "$norm" == "no" ]]
}

print_usage() {
  sed -n '2,43p' "$0" | sed 's/^# \?//'
  exit 0
}

prompt_or_die() {
  local prompt="$1"
  local default="${2:-}"
  local out
  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    [[ -n "$default" ]] && {
      echo "$default"
      return
    }
    die "Missing required input ($prompt) in --non-interactive mode"
  fi
  if [[ -n "$default" ]]; then
    read -rp "$prompt [$default]: " out
    echo "${out:-$default}"
  else
    read -rp "$prompt: " out
    echo "$out"
  fi
}

prompt_secret_value() {
  local prompt="$1"
  local out
  read -rsp "$prompt: " out
  echo >&2
  echo "$out"
}

# Prompt repeatedly until the user supplies a non-empty value. Honors
# NON_INTERACTIVE by returning empty and letting the caller decide. Echoes
# the resulting value on stdout so it's capture-able via $(...).
read_nonempty() {
  local label="$1"
  local value=""
  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    return 0
  fi
  while [[ -z "$value" ]]; do
    read -rp "$label: " value || {
      # EOF (e.g. ctrl-D) — surface as empty and bail.
      echo >&2
      return 1
    }
    if [[ -z "$value" ]]; then
      log_warn "Value cannot be empty — try again, or Ctrl-C to abort."
    fi
  done
  printf '%s' "$value"
}

# Prompt until the user picks a numeric choice in [1,max]. Returns the choice.
# `default` (optional) is returned for empty input. EOF returns 1.
read_menu_choice() {
  local label="$1"
  local max="$2"
  local default="${3:-}"
  local choice=""
  while :; do
    local displayed="$label"
    [[ -n "$default" ]] && displayed="$label [$default]"
    if ! read -rp "$displayed: " choice; then
      echo >&2
      return 1
    fi
    choice="${choice:-$default}"
    if [[ "$choice" =~ ^[0-9]+$ ]] && ((choice >= 1 && choice <= max)); then
      printf '%s' "$choice"
      return 0
    fi
    log_warn "Please enter a number between 1 and $max."
  done
}

on_interrupt() {
  echo
  log_warn "Interrupted. Exiting; rerun the script to resume."
  exit 130
}
trap on_interrupt INT

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --project-id)
        PROJECT_ID="$2"
        shift 2
        ;;
      --region)
        REGION="$2"
        shift 2
        ;;
      --primary-domain)
        PRIMARY_DOMAIN="$2"
        shift 2
        ;;
      --secondary-domain)
        SECONDARY_DOMAIN="$2"
        shift 2
        ;;
      --webauthn-rp-id)
        WEBAUTHN_RP_ID="$2"
        shift 2
        ;;
      --dns-provider)
        DNS_PROVIDER="$2"
        shift 2
        ;;
      --overwrite)
        OVERWRITE_TFVARS="true"
        shift
        ;;
      --auto-approve)
        AUTO_APPROVE="true"
        shift
        ;;
      --non-interactive)
        NON_INTERACTIVE="true"
        shift
        ;;
      --skip-prereqs)
        SKIP_PREREQS="true"
        shift
        ;;
      --skip-domain-check)
        SKIP_DOMAIN_CHECK="true"
        shift
        ;;
      --skip-tfvars)
        SKIP_TFVARS="true"
        shift
        ;;
      --skip-terraform)
        SKIP_TERRAFORM="true"
        shift
        ;;
      --skip-secrets)
        SKIP_SECRETS="true"
        shift
        ;;
      --skip-image)
        SKIP_IMAGE="true"
        shift
        ;;
      --skip-dns)
        SKIP_DNS="true"
        shift
        ;;
      --skip-verify)
        SKIP_VERIFY="true"
        shift
        ;;
      -h | --help) print_usage ;;
      *) die "Unknown flag: $1 (run $0 --help)" ;;
    esac
  done
}

# ----------------------------------------------------------------------------
# Auto-detection helpers
# ----------------------------------------------------------------------------

# Read `project_id = "foo"` out of an existing tfvars file.
read_tfvars_project_id() {
  local file="$1"
  [[ -f "$file" ]] || return 1
  awk -F'=' '/^[[:space:]]*project_id[[:space:]]*=/ {
        gsub(/[[:space:]"]/, "", $2); print $2; exit
    }' "$file"
}

detect_project_id() {
  if [[ -n "$PROJECT_ID" ]]; then
    log_info "Using project id from flag/env: $PROJECT_ID"
    return
  fi

  local candidates=()

  local committed
  committed=$(read_tfvars_project_id "$TFVARS_PATH" 2> /dev/null || true)
  [[ -n "$committed" ]] && candidates+=("$committed (infra/terraform/production.tfvars)")

  if [[ -d "${REPO_ROOT}/.logdate/deploy" ]]; then
    local bootstrap_dir
    while IFS= read -r bootstrap_dir; do
      local id
      id=$(basename "$bootstrap_dir")
      [[ -n "$id" ]] && candidates+=("$id (.logdate/deploy/$id)")
    done < <(find "${REPO_ROOT}/.logdate/deploy" -mindepth 1 -maxdepth 1 -type d 2> /dev/null || true)
  fi

  local active
  active=$(gcloud config get-value project 2> /dev/null || true)
  [[ -n "$active" && "$active" != "(unset)" ]] && candidates+=("$active (gcloud active project)")

  if [[ ${#candidates[@]} -eq 0 ]]; then
    if [[ "$NON_INTERACTIVE" == "true" ]]; then
      die "GCP project ID is required — pass --project-id or set LOGDATE_GCP_PROJECT_ID."
    fi
    PROJECT_ID=$(read_nonempty "GCP project ID") || die "Aborted."
    return
  fi

  # Dedupe by project id — keep the first candidate per id, which preserves
  # the most authoritative source (committed tfvars > bootstrap state > gcloud).
  local -a unique_candidates=()
  local seen=""
  for c in "${candidates[@]}"; do
    local id="${c%% *}"
    case "$seen" in
      *"|$id|"*) ;;
      *)
        unique_candidates+=("$c")
        seen="$seen|$id|"
        ;;
    esac
  done
  candidates=("${unique_candidates[@]}")

  if [[ ${#candidates[@]} -eq 1 ]]; then
    local only="${candidates[0]}"
    PROJECT_ID="${only%% *}"
    log_info "Detected project id: $only"
    return
  fi

  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    log_error "Multiple project candidates found:"
    printf '  • %s\n' "${candidates[@]}"
    die "Pass --project-id to disambiguate in non-interactive mode"
  fi

  echo "Multiple project candidates found:"
  local i=1
  for c in "${candidates[@]}"; do
    printf '  %d) %s\n' "$i" "$c"
    ((i++))
  done
  local choice
  choice=$(read_menu_choice "Select" "${#candidates[@]}" 1) || die "Aborted."
  local picked="${candidates[$((choice - 1))]}"
  PROJECT_ID="${picked%% *}"
  log_info "Using $PROJECT_ID"
}

detect_dns_provider() {
  if [[ -n "$DNS_PROVIDER" ]]; then
    return
  fi

  if [[ -n "${CLOUDFLARE_API_TOKEN:-}" ]]; then
    DNS_PROVIDER="cloudflare"
    log_info "CLOUDFLARE_API_TOKEN present → DNS provider: cloudflare"
    return
  fi

  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    DNS_PROVIDER="manual"
    return
  fi

  echo
  echo "Pick a DNS provider for publishing the Cloud Run mapping records:"
  echo "  1) manual      — script prints records for you to copy into your registrar"
  echo "  2) cloudflare  — script publishes records via the Cloudflare API (needs CLOUDFLARE_API_TOKEN)"
  local choice
  choice=$(read_menu_choice "Select" 2 1) || {
    DNS_PROVIDER="manual"
    return
  }
  case "$choice" in
    1) DNS_PROVIDER="manual" ;;
    2) DNS_PROVIDER="cloudflare" ;;
  esac
}

# ----------------------------------------------------------------------------
# gcloud identity confirmation — prints the active account for audit, but
# doesn't prompt. Pass --confirm-account to force the interactive picker.
# ----------------------------------------------------------------------------
confirm_gcloud_identity() {
  log_phase "Confirming gcloud identity"

  local required=(gcloud terraform docker jq openssl curl)
  for cmd in "${required[@]}"; do
    command -v "$cmd" > /dev/null 2>&1 || die "Missing required command: $cmd"
  done

  local accounts
  accounts=$(gcloud auth list --format='value(account)' 2> /dev/null || true)
  if [[ -z "$accounts" ]]; then
    if [[ "$NON_INTERACTIVE" == "true" ]]; then
      die "No gcloud accounts configured — run 'gcloud auth login' first"
    fi
    log_warn "No gcloud accounts configured — launching browser login"
    gcloud auth login
    accounts=$(gcloud auth list --format='value(account)' 2> /dev/null || true)
  fi

  local active
  active=$(gcloud config get-value account 2> /dev/null || true)
  if [[ -z "$active" || "$active" == "(unset)" ]]; then
    die "gcloud has accounts configured but none is active. Run: gcloud config set account <email>"
  fi

  local current_project
  current_project=$(gcloud config get-value project 2> /dev/null || true)
  [[ "$current_project" == "(unset)" ]] && current_project=""

  echo
  printf '  %bAccount:%b %s\n' "$BOLD" "$NC" "$active"
  printf '  %bProject:%b %s\n' "$BOLD" "$NC" "${current_project:-<none>}"
  echo

  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    log_info "Proceeding with $active (non-interactive)"
    return
  fi

  local confirm
  read -rp "Is this the account you want to deploy with? [Y/n] " confirm
  if [[ ! "$confirm" =~ ^[nN]$ ]]; then
    return
  fi

  echo "Available accounts:"
  local -a ids=()
  local i=1
  while IFS= read -r acct; do
    [[ -z "$acct" ]] && continue
    printf '  %d) %s\n' "$i" "$acct"
    ids+=("$acct")
    ((i++))
  done <<< "$accounts"
  printf '  %d) Log in as a different account\n' "$i"

  local choice
  choice=$(read_menu_choice "Select" "$i" 1) || return 0

  if ((choice == i)); then
    gcloud auth login
    active=$(gcloud config get-value account 2> /dev/null || true)
  else
    active="${ids[$((choice - 1))]}"
    gcloud config set account "$active"
  fi
  log_info "Now using gcloud account: $active"
}

# ----------------------------------------------------------------------------
# Phase 0 — Prereq validation
# ----------------------------------------------------------------------------
phase_0_prereqs() {
  log_phase "Phase 0 — Prereq validation"

  log_info "Verifying project ${PROJECT_ID}…"
  local current_env
  current_env=$(gcloud projects describe "$PROJECT_ID" \
    --format='value(labels.environment)' --quiet 2> /dev/null) \
    || die "Cannot read project $PROJECT_ID — check the ID and the gcloud account's viewer access."
  gcloud config set project "$PROJECT_ID" --quiet > /dev/null
  log_info "Active project: $PROJECT_ID"

  # `--update-labels` lives only in `gcloud alpha projects update` (GA only
  # supports `--name`). Non-fatal; the tag is cosmetic.
  if [[ "$current_env" == "production" ]]; then
    log_info "Project already tagged environment=production"
  elif gcloud components list --filter='id=alpha AND state.name=Installed' \
    --format='value(id)' --quiet 2> /dev/null | grep -q '^alpha$'; then
    log_info "Tagging $PROJECT_ID with environment=production (via gcloud alpha)"
    gcloud alpha projects update "$PROJECT_ID" \
      --update-labels=environment=production --quiet > /dev/null \
      || log_warn "Tagging failed — cosmetic only; continuing."
  else
    log_warn "Skipping project label — the CLI flag lives in the alpha channel. To set it later:"
    log_warn "  gcloud components install alpha --quiet"
    log_warn "  gcloud alpha projects update $PROJECT_ID --update-labels=environment=production"
  fi

  # Billing must be linked, or nothing downstream will work. Use GA
  # `gcloud billing` (not beta) to avoid a component-install prompt, and
  # wrap in an explicit backgrounded-call timeout so a silent stall on the
  # billing API is visible rather than indefinite.
  log_info "Checking billing link…"
  local billing_enabled=""
  if billing_enabled=$(gcloud billing projects describe "$PROJECT_ID" \
    --format='value(billingEnabled)' --quiet 2> /dev/null); then
    :
  else
    log_warn "gcloud billing describe failed or is unavailable; skipping billing check."
    log_warn "If deploy fails with quota/billing errors, link a billing account at:"
    log_warn "  https://console.cloud.google.com/billing/linkedaccount?project=$PROJECT_ID"
    billing_enabled="skipped"
  fi
  case "$billing_enabled" in
    True) log_info "Billing is linked to $PROJECT_ID" ;;
    skipped) ;;
    *) die "Project $PROJECT_ID has no active billing account. Link one: https://console.cloud.google.com/billing/linkedaccount?project=$PROJECT_ID" ;;
  esac

  # docker buildx plugin is required by scripts/deploy-cloud-run.sh.
  log_info "Checking docker buildx…"
  if ! docker buildx version > /dev/null 2>&1; then
    die "docker buildx plugin is required (install: https://docs.docker.com/build/install-buildx/)"
  fi

  ensure_docker_daemon_running

  # Enable only the APIs the script calls directly before Terraform runs.
  # Terraform's google_project_service.required enables the rest on apply.
  # `services enable` is idempotent, so skip the `services list` diff.
  log_info "Ensuring script-level APIs are enabled…"
  gcloud services enable --project "$PROJECT_ID" --quiet \
    run.googleapis.com \
    artifactregistry.googleapis.com \
    secretmanager.googleapis.com
}

# ----------------------------------------------------------------------------
# Phase 1 — Domain ownership pre-flight
# ----------------------------------------------------------------------------
is_covered_by_verified() {
  # `gcloud domains list-user-verified` returns apex/registrable domains,
  # and a verified apex covers every subdomain under it for Cloud Run
  # domain mapping. So `logdate.app` in the list covers `cloud.logdate.app`.
  local domain="$1"
  local verified_list="$2"
  local entry
  while IFS= read -r entry; do
    [[ -z "$entry" ]] && continue
    if [[ "$domain" == "$entry" || "$domain" == *".$entry" ]]; then
      return 0
    fi
  done <<< "$verified_list"
  return 1
}

phase_1_domain_check() {
  log_phase "Phase 1 — Domain ownership pre-flight"

  local verified
  verified=$(gcloud domains list-user-verified --format='value(id)' 2> /dev/null || true)

  local targets=("$PRIMARY_DOMAIN")
  [[ -n "$SECONDARY_DOMAIN" ]] && targets+=("$SECONDARY_DOMAIN")

  local unverified=()
  for domain in "${targets[@]}"; do
    if is_covered_by_verified "$domain" "$verified"; then
      log_info "$domain — covered by a verified apex"
    else
      unverified+=("$domain")
    fi
  done

  if [[ ${#unverified[@]} -eq 0 ]]; then
    return
  fi

  log_warn "The following domains are not verified for $(gcloud config get-value account 2> /dev/null):"
  for domain in "${unverified[@]}"; do
    printf '  • %s\n' "$domain"
    printf '    verify:  gcloud domains verify %s\n' "$domain"
    printf '    view:    https://search.google.com/search-console?resource_id=sc-domain:%s\n' "$domain"
  done

  cat << 'EOF'

Notes:
  • `gcloud domains verify` launches the Google Site Verification browser
    flow — this is the authoritative path. Publish the TXT record Google
    shows at your DNS provider (paste it into the Cloudflare dashboard if
    that's where the zone lives), click "Verify", then come back here.
  • The "view" URL drops you into the Search Console property if it already
    exists — useful when Site Verification and Search Console have drifted.
  • `gcloud domains list-user-verified` occasionally lags verifications done
    directly in Search Console. If you're confident the domain is verified,
    answer "skip" below to proceed anyway — Cloud Run will reject the
    domain mapping at terraform-apply time if it really isn't verified.
EOF

  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    die "Halting until domains are verified"
  fi

  echo
  echo "Next action:"
  echo "  1) Run 'gcloud domains verify' for each unverified domain now (recommended)"
  echo "  2) I've already verified them; re-check 'gcloud domains list-user-verified'"
  echo "  3) Skip the check; trust the domains are verified and let Cloud Run gate it"
  echo "  4) Abort"

  local choice
  choice=$(read_menu_choice "Select" 4 1) || die "Aborted."
  case "$choice" in
    1)
      for domain in "${unverified[@]}"; do
        log_info "Opening verification flow for $domain"
        gcloud domains verify "$domain" || log_warn "gcloud domains verify $domain exited non-zero; verify manually"
      done
      read -rp "Press Enter once every domain above shows as verified " _ || true
      ;;
    2) ;;
    3)
      log_warn "Skipping verification check — Cloud Run will validate at apply time."
      return
      ;;
    4) die "Aborted by user" ;;
  esac

  verified=$(gcloud domains list-user-verified --format='value(id)' 2> /dev/null || true)
  local still_missing=()
  for domain in "${unverified[@]}"; do
    is_covered_by_verified "$domain" "$verified" || still_missing+=("$domain")
  done
  if [[ ${#still_missing[@]} -eq 0 ]]; then
    log_info "All target domains verified"
    return
  fi
  log_warn "'gcloud domains list-user-verified' still doesn't show: ${still_missing[*]}"
  log_warn "This can be a cache/scope drift — check the Search Console URLs above."
  read -rp "Proceed anyway and let Cloud Run decide at apply time? [y/N] " fallback
  reply_is_yes "$fallback" || die "Aborting; rerun once verification is reflected. (received: '$fallback')"
}

# ----------------------------------------------------------------------------
# Phase 2 — Generate production.tfvars
# ----------------------------------------------------------------------------
phase_2_tfvars() {
  log_phase "Phase 2 — Generate $TFVARS_PATH"

  if [[ -f "$TFVARS_PATH" && "$OVERWRITE_TFVARS" != "true" ]]; then
    log_info "$TFVARS_PATH already exists; leaving unchanged (pass --overwrite to regenerate)"
    return
  fi

  # WebAuthn rpId is pinned to `logdate.app` so passkeys work across every
  # *.logdate.app origin (primary auth lives at cloud.logdate.app). Override
  # with --webauthn-rp-id only if you really know why. The rpId does NOT
  # cover *.hypertext.studio origins — those serve API-only traffic for
  # clients already holding a JWT.
  [[ -z "$WEBAUTHN_RP_ID" ]] && WEBAUTHN_RP_ID="logdate.app"
  local webauthn_origin="https://cloud.logdate.app"

  local allowed_origins="https://$PRIMARY_DOMAIN"
  local domains_list="[\"$PRIMARY_DOMAIN\""
  if [[ -n "$SECONDARY_DOMAIN" ]]; then
    allowed_origins="$allowed_origins,https://$SECONDARY_DOMAIN"
    domains_list="$domains_list, \"$SECONDARY_DOMAIN\""
  fi
  domains_list="$domains_list]"

  local github_repo
  github_repo=$(git -C "$REPO_ROOT" config --get remote.origin.url 2> /dev/null \
    | sed -E 's#(git@github\.com:|https?://github\.com/)##; s#\.git$##' \
    || true)

  cat > "$TFVARS_PATH" << EOF
# Generated by scripts/deploy-production.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ).
# Safe to commit; values here are non-sensitive configuration only. Secrets
# live in Secret Manager (see cloud_run_secret_env below).

project_id      = "$PROJECT_ID"
region          = "$REGION"
service_name    = "logdate-server"
# Placeholder image used ONLY for the initial terraform apply. The real
# image is pushed + deployed by scripts/deploy-cloud-run.sh in Phase 5;
# terraform's lifecycle { ignore_changes = [image] } prevents drift after.
cloud_run_image = "us-docker.pkg.dev/cloudrun/container/hello"
webauthn_rp_id  = "$WEBAUTHN_RP_ID"
webauthn_origin = "$webauthn_origin"

enable_github_oidc = true
github_repo        = "$github_repo"

enable_domain_mapping = true
domains               = $domains_list

create_gcs_bucket = true
gcs_bucket_name   = "logdate-media-$PROJECT_ID"

# Default to "bring your own Postgres" to keep cold-start costs near zero —
# populate DATABASE_URL in Secret Manager with a Neon/Supabase/Cloud SQL
# connection string. Flip this to $(true) only if you want Terraform to
# provision a managed Cloud SQL instance (and pay the monthly baseline).
create_cloud_sql_instance = false

# Keys and values here must match scripts/render-cloud-run-contract.sh, which requires every
# one of them and rejects anything it does not recognise. An earlier version of this template
# emitted ALLOWED_ORIGINS/REQUIRE_HTTPS and omitted the rest, which made every generated
# production contract fail to render.
cloud_run_env = {
  LOGDATE_ENV                     = "production"
  LOGDATE_EXPECT_FIRST_PARTY      = "true"
  LOGDATE_DEPLOYMENT_KIND         = "first_party"
  LOGDATE_SERVER_DISPLAY_NAME     = "LogDate Cloud"
  LOGDATE_PUBLIC_ORIGIN           = "https://$PRIMARY_DOMAIN"
  ATPROTO_PDS_SERVICE_URL         = "https://$PRIMARY_DOMAIN"
  ATPROTO_HANDLE_DOMAIN           = "$WEBAUTHN_RP_ID"
  BILLING_PROVIDER                = "play"
  SERVER_ENCRYPTION_ENABLED       = "true"
  SYNC_MEDIA_SIGNED_URLS          = "true"
  SYNC_MEDIA_SIGNED_URL_TTL_HOURS = "1"
  TRUSTED_PROXY_HOPS              = "0"
  AUTO_MIGRATE                    = "false"
}

# Secret versions are pinned; $(latest) is prohibited in first-party contracts.
cloud_run_secret_env = {
  DATABASE_URL             = { secret_id = "logdate-db-url", version = "1" }
  DATABASE_USER            = { secret_id = "logdate-db-user", version = "1" }
  DATABASE_PASSWORD        = { secret_id = "logdate-db-password", version = "1" }
  JWT_SECRET               = { secret_id = "logdate-jwt-secret", version = "1" }
  ATPROTO_SIGNING_KEY_KEK  = { secret_id = "logdate-atproto-signing-key-kek", version = "1" }
  ATPROTO_SESSION_SECRET   = { secret_id = "logdate-atproto-session-secret", version = "1" }
  SERVER_ENCRYPTION_KEY    = { secret_id = "logdate-server-encryption-key", version = "1" }
  SERVER_ENCRYPTION_KEY_ID = { secret_id = "logdate-server-encryption-key-id", version = "1" }
  HEALTH_INTERNAL_TOKEN    = { secret_id = "logdate-health-internal-token", version = "1" }
}

# The renderer additionally requires an $(android_signing_certificates) block with exactly an
# $(upload) and a $(play_app_signing) entry. Those fingerprints only exist once the Play
# application does, so add them by hand before the first production deploy.

create_secrets = true
EOF

  log_info "Wrote $TFVARS_PATH"
  log_info "Remember to git-add and commit this file — it documents the live infra."
}

# ----------------------------------------------------------------------------
# Phase 3 — Terraform apply
# ----------------------------------------------------------------------------

# Terraform uses Application Default Credentials, not the gcloud user session.
# Sensitive creates (service accounts, Workload Identity pools) require a
# fresh reauth persistent token (RAPT); if yours has expired you'll get
# `invalid_grant / invalid_rapt` mid-apply. Probe by making a small
# privileged call and auto-refresh if it fails.
ensure_adc_fresh() {
  if ! gcloud auth application-default print-access-token --quiet > /dev/null 2>&1; then
    if [[ "$NON_INTERACTIVE" == "true" ]]; then
      die "No Application Default Credentials — run 'gcloud auth application-default login' first."
    fi
    log_warn "No Application Default Credentials found — launching login."
    gcloud auth application-default login
    return
  fi

  # Probe RAPT freshness by reading the project — cheaper than listing
  # service accounts. Returns 200 when auth is good, 401/403 on expired RAPT.
  local probe_output
  probe_output=$(curl -fsS -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $(gcloud auth application-default print-access-token --quiet)" \
    "https://cloudresourcemanager.googleapis.com/v1/projects/$PROJECT_ID" 2>&1 || echo "000")

  if [[ "$probe_output" == "200" ]]; then
    log_info "Application Default Credentials OK."
    return
  fi

  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    die "ADC probe returned $probe_output — refresh with 'gcloud auth application-default login'."
  fi

  log_warn "ADC probe returned HTTP $probe_output — refreshing reauth token."
  gcloud auth application-default login
}

# Core terraform apply with optional extra -var overrides. Handles init,
# plan, the y/n prompt, and plan-file cleanup. Auto-confirms when the plan
# is purely additive (no destroys, no replacements) — only asks the user
# when something destructive is about to happen.
terraform_plan_and_apply() {
  local label="$1"
  shift # remaining args are passed to terraform plan/apply as -var=… etc.

  ensure_adc_fresh

  (
    cd "$TF_DIR"
    terraform init -input=false
  )
  reconcile_state_with_gcp

  (
    cd "$TF_DIR"
    terraform plan -input=false -var-file=production.tfvars "$@" -out=prod.tfplan

    # Inspect the plan for destroys/replaces. Surface them concretely in
    # the confirmation prompt; hard-refuse the cases that will wreck the
    # deploy regardless of what the user types.
    local destroyed_list
    destroyed_list=$(terraform show -json prod.tfplan \
      | jq -r '[.resource_changes[]? | select((.change.actions | index("delete")) or (.change.actions | index("replace"))) | .address] | .[]')
    local destructive=0
    [[ -n "$destroyed_list" ]] && destructive=$(wc -l <<< "$destroyed_list" | tr -d ' ')

    # Auto-heal plans that would destroy protected resources. Skip this
    # apply — the next phase's plan, without the `enable_cloud_run_service=false`
    # override, will produce a clean no-op. Don't touch state; that's what
    # triggered the "already exists" 409 last time.
    local protected_destroys=""
    protected_destroys=$(grep -E '^(google_cloud_run_v2_service\.server|google_sql_database_instance\.postgres|google_storage_bucket\.media|google_secret_manager_secret\.env)' <<< "$destroyed_list" || true)
    if [[ -n "$protected_destroys" ]]; then
      log_info "Auto-healing: plan would destroy protected resource(s). Skipping this apply; next phase reconciles:"
      while IFS= read -r addr; do
        printf '  %b•%b %s\n' "$YELLOW" "$NC" "$addr"
      done <<< "$protected_destroys"
      rm -f prod.tfplan
      exit 0 # subshell-only — falls through to the next phase in main()
    fi

    if [[ "$destructive" -eq 0 ]]; then
      if [[ "$NON_INTERACTIVE" != "true" && "$AUTO_APPROVE" != "true" ]]; then
        log_info "Plan is purely additive; auto-applying ($label)."
      fi
    else
      log_warn "Plan DESTROYS the following $destructive resource(s):"
      while IFS= read -r addr; do
        printf '  %b-%b %s\n' "$RED" "$NC" "$addr"
      done <<< "$destroyed_list"
      if [[ "$NON_INTERACTIVE" == "true" ]]; then
        die "Refusing to apply a destructive plan non-interactively. Inspect and rerun with --auto-approve if intentional."
      fi
      if [[ "$AUTO_APPROVE" != "true" ]]; then
        local confirm
        read -rp "Type 'destroy' exactly to confirm, anything else aborts: " confirm
        [[ "$confirm" == "destroy" ]] || die "Aborted — no changes made."
      fi
    fi
    terraform apply -input=false prod.tfplan
    rm -f prod.tfplan
  )
}

# Returns 0 iff `google_cloud_run_v2_service.server[0]` is already in state.
cloud_run_in_state() {
  (cd "$TF_DIR" && terraform state list 2> /dev/null \
    | grep -q '^google_cloud_run_v2_service\.server\[0\]$')
}

# Pull `project_id` / `region` / `service_name` out of the active tfvars so we
# can compute canonical import IDs without hard-coding.
tfvars_get() {
  local key="$1"
  awk -F'=' -v key="$key" '$1 ~ "^[[:space:]]*"key"[[:space:]]*$" {
        gsub(/[[:space:]"]/, "", $2); print $2; exit
    }' "$TFVARS_PATH"
}

# If a resource is tracked by us (known import-id format) and exists in GCP
# but not in state, import it. Idempotent and silent when state already has it.
reconcile_state_with_gcp() {
  local project region service
  project=$(tfvars_get project_id)
  region=$(tfvars_get region)
  service=$(tfvars_get service_name)
  [[ -z "$project" || -z "$region" || -z "$service" ]] && return 0

  local state
  state=$(cd "$TF_DIR" && terraform state list 2> /dev/null || true)

  _import_if_missing() {
    local addr="$1" id="$2"
    grep -qxF "$addr" <<< "$state" && return 0
    log_info "Auto-importing $addr (exists in GCP, missing from state)"
    (cd "$TF_DIR" && terraform import -var-file=production.tfvars "$addr" "$id") > /dev/null 2>&1 \
      || log_warn "  · import failed — terraform may still propose create and fail at apply"
  }

  # Cloud Run service — import only if it's actually alive in GCP.
  if gcloud run services describe "$service" --region="$region" --project="$project" --format='value(name)' --quiet > /dev/null 2>&1; then
    _import_if_missing "google_cloud_run_v2_service.server[0]" \
      "projects/$project/locations/$region/services/$service"
  fi

  # Public invoker IAM binding — only meaningful if the service exists.
  if grep -qxF 'google_cloud_run_v2_service.server[0]' <<< "$state" \
    || gcloud run services describe "$service" --region="$region" --project="$project" --format='value(name)' --quiet > /dev/null 2>&1; then
    _import_if_missing "google_cloud_run_v2_service_iam_member.public_invoker[0]" \
      "projects/$project/locations/$region/services/$service roles/run.invoker allUsers"
  fi

  # Domain mappings — one per entry in `domains = [...]`. Each mapping's ID
  # is `locations/<region>/namespaces/<project>/domainmappings/<domain>`.
  local domains
  domains=$(awk '/^[[:space:]]*domains[[:space:]]*=/,/\]/' "$TFVARS_PATH" \
    | grep -oE '"[^"]+"' | tr -d '"')
  while IFS= read -r domain; do
    [[ -z "$domain" ]] && continue
    if gcloud beta run domain-mappings describe --domain="$domain" --region="$region" --project="$project" --format='value(metadata.name)' --quiet > /dev/null 2>&1; then
      _import_if_missing "google_cloud_run_domain_mapping.default[\"$domain\"]" \
        "locations/$region/namespaces/$project/domainmappings/$domain"
    fi
  done <<< "$domains"
}

# Starts Docker Desktop on macOS if the daemon isn't running, then waits up
# to 60s for it. A clean-run deploy should never have to fail just because
# Docker wasn't booted first.
ensure_docker_daemon_running() {
  if docker info > /dev/null 2>&1; then
    return 0
  fi
  case "$(uname -s)" in
    Darwin)
      log_warn "Docker daemon isn't running — launching Docker.app and waiting up to 60s…"
      open -a Docker > /dev/null 2>&1 || die "Couldn't launch Docker.app. Start Docker Desktop manually and rerun."
      ;;
    Linux)
      log_warn "Docker daemon isn't running — try 'sudo systemctl start docker' or start Docker Desktop."
      ;;
    *)
      log_warn "Docker daemon isn't running on an unrecognized platform — start it manually."
      ;;
  esac
  local i=0
  until docker info > /dev/null 2>&1; do
    i=$((i + 2))
    ((i > 60)) && die "Docker daemon did not become ready within 60s."
    sleep 2
  done
  log_info "Docker daemon is ready."
}

# Phase 3a — bootstrap-only infra pass. Runs `terraform apply` with
# `enable_cloud_run_service=false` so Cloud Run isn't created before its
# secrets and image exist. This is ONLY safe when Cloud Run is not yet
# managed by Terraform — on a re-run, the same override would destroy the
# already-created service and domain mappings, so we skip outright.
phase_3a_infra() {
  log_phase "Phase 3a — applying infra (APIs, IAM, registry, secrets, bucket)"

  [[ -f "$TFVARS_PATH" ]] || die "$TFVARS_PATH missing — run without --skip-tfvars first"

  if cloud_run_in_state; then
    log_info "Cloud Run already managed by Terraform — skipping 3a to avoid destroying it."
    log_info "Phase 3b will reconcile the full config, no-op if everything is in sync."
    return
  fi

  terraform_plan_and_apply "infra" -var=enable_cloud_run_service=false
}

# Phase 3b — full apply. Idempotent: creates Cloud Run + domain mappings on
# the first pass, and acts as a no-op reconciler on every subsequent run.
phase_3b_cloud_run() {
  log_phase "Phase 3b — applying Cloud Run service + domain mappings"

  [[ -f "$TFVARS_PATH" ]] || die "$TFVARS_PATH missing — run without --skip-tfvars first"

  terraform_plan_and_apply "cloud-run"
}

# ----------------------------------------------------------------------------
# Phase 4 — Populate Secret Manager
# ----------------------------------------------------------------------------
# Optional secrets (OIDC, Redis) are wired up only when they're *both*
# referenced in production.tfvars AND have a value in production.env. This
# avoids writing empty-payload sentinels, which Cloud Run rejects when
# resolving the secret's `latest` version at boot.

PRODUCTION_ENV_FILE="${TF_DIR}/production.env"

secret_has_version() {
  gcloud secrets versions list "$1" \
    --project "$PROJECT_ID" \
    --limit 1 \
    --format='value(name)' 2> /dev/null | grep -q .
}

put_secret_value() {
  local secret_id="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    log_warn "Refusing to write empty version for $secret_id — skipping"
    return 1
  fi
  printf '%s' "$value" | gcloud secrets versions add "$secret_id" \
    --project "$PROJECT_ID" \
    --data-file=- > /dev/null
  log_info "  · wrote version for $secret_id"
}

# Returns 0 if the secret id is referenced in the current production.tfvars
# cloud_run_secret_env block. Used to decide whether to populate an optional
# secret or ignore it silently.
secret_is_referenced() {
  grep -qE "secret_id[[:space:]]*=[[:space:]]*\"$1\"" "$TFVARS_PATH"
}

# Export key=value pairs from $PRODUCTION_ENV_FILE as env vars, treating
# everything after the first `=` as a literal value. `source`-ing the file
# directly would expand `$var` references inside secret values (common in
# JDBC URLs and redis tokens), which is wrong.
load_production_env() {
  [[ -f "$PRODUCTION_ENV_FILE" ]] || return 0
  log_info "Reading pre-populated secrets from $PRODUCTION_ENV_FILE"
  while IFS= read -r line; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    local key="${line%%=*}"
    local value="${line#*=}"
    # Strip matching outer single/double quotes if present.
    if [[ ("$value" == \"*\" || "$value" == \'*\') && ${#value} -ge 2 ]]; then
      value="${value:1:${#value}-2}"
    fi
    export "$key=$value"
  done < "$PRODUCTION_ENV_FILE"
}

# Extract a query-param value from a URL. Anchored on `?` or `&` so a key
# that appears as a substring of another token (e.g. `password=` within a
# trailing `resetpassword=`) doesn't match. Returns 1 when absent.
url_param() {
  local url="$1"
  local key="$2"
  awk -v key="$key" '
        {
            n = split($0, parts, /[?&]/)
            for (i = 1; i <= n; i++) {
                if (index(parts[i], key "=") == 1) {
                    sub("^" key "=", "", parts[i])
                    print parts[i]
                    exit 0
                }
            }
            exit 1
        }
    ' <<< "$url"
}

# Resolve a required secret's value and write it to Secret Manager. Precedence:
#   1. env var (from production.env or exported shell env)
#   2. `url_param` against DATABASE_URL when $url_key is non-empty
#   3. interactive prompt (unless NON_INTERACTIVE)
#
# Uses `eval` for indirect expansion because bash 3.2 on macOS doesn't
# handle `${!var:-default}` the same way bash 4+ does.
resolve_and_put_secret() {
  local secret_id="$1"
  local env_key="$2"
  local url_key="$3"
  local prompt_label="$4"

  if secret_has_version "$secret_id"; then
    log_info "$secret_id already has a version — skipping"
    return
  fi

  local value=""
  eval "value=\${${env_key}:-}"
  if [[ -z "$value" && -n "$url_key" ]]; then
    value=$(url_param "${DATABASE_URL:-}" "$url_key" || true)
    if [[ -n "$value" ]]; then
      log_info "Derived $env_key from DATABASE_URL"
    fi
  fi
  if [[ -z "$value" ]]; then
    if [[ "$NON_INTERACTIVE" == "true" ]]; then
      die "$env_key missing — populate $PRODUCTION_ENV_FILE before --non-interactive runs."
    fi
    value=$(read_nonempty "$prompt_label") || die "Aborted."
  fi
  put_secret_value "$secret_id" "$value"
}

phase_4_secrets() {
  log_phase "Phase 4 — Populate Secret Manager"

  load_production_env

  local jwt_secret_existed=false
  if secret_has_version logdate-jwt-secret; then
    jwt_secret_existed=true
    log_info "logdate-jwt-secret already has a version — skipping"
  else
    log_info "Generating JWT secret via openssl rand -base64 48"
    put_secret_value logdate-jwt-secret "$(openssl rand -base64 48 | tr -d '\n')"
  fi

  # Signing keys stored before this secret existed were encrypted with the JWT secret, so an
  # existing deployment must copy that value rather than start from a new one.
  if secret_has_version logdate-atproto-signing-key-kek; then
    log_info "logdate-atproto-signing-key-kek already has a version — skipping"
  elif [[ "$jwt_secret_existed" == "true" ]]; then
    die "logdate-atproto-signing-key-kek has no version, but logdate-jwt-secret already did. Copy the exact bytes of the pinned logdate-jwt-secret version into logdate-atproto-signing-key-kek version 1 (server/docs/environment-variables.md#atproto_signing_key_kek), then rerun."
  else
    log_info "Generating AT Protocol signing-key secret via openssl rand -base64 48"
    put_secret_value logdate-atproto-signing-key-kek "$(openssl rand -base64 48 | tr -d '\n')"
  fi

  # A new session secret only signs out AT Protocol client sessions, so it is always generated fresh.
  if secret_has_version logdate-atproto-session-secret; then
    log_info "logdate-atproto-session-secret already has a version — skipping"
  else
    log_info "Generating AT Protocol session secret via openssl rand -base64 48"
    put_secret_value logdate-atproto-session-secret "$(openssl rand -base64 48 | tr -d '\n')"
  fi

  resolve_and_put_secret logdate-db-url DATABASE_URL "" "DATABASE_URL (jdbc:postgresql://host/db?user=X&password=Y&sslmode=require)"
  resolve_and_put_secret logdate-db-user DATABASE_USER user "DATABASE_USER"
  resolve_and_put_secret logdate-db-password DATABASE_PASSWORD password "DATABASE_PASSWORD"

  # Optional: only populated if the secret is referenced in production.tfvars
  # AND a value is in production.env. No empty payloads get written.
  populate_optional_secret logdate-google-oidc-client-ids "${GOOGLE_OIDC_CLIENT_IDS:-}"
  populate_optional_secret logdate-redis-url "${REDIS_URL:-}"

  log_info "Secret Manager population complete"
}

populate_optional_secret() {
  local secret_id="$1"
  local value="$2"

  if secret_has_version "$secret_id"; then
    log_info "$secret_id already has a version — skipping"
    return
  fi

  if ! secret_is_referenced "$secret_id"; then
    log_info "$secret_id not referenced in tfvars — skipping"
    return
  fi

  if [[ -z "$value" ]]; then
    log_warn "$secret_id is referenced in tfvars but no value supplied in $PRODUCTION_ENV_FILE — either add it there or remove the ref from cloud_run_secret_env."
    return
  fi

  log_info "Loaded value for $secret_id from $PRODUCTION_ENV_FILE"
  put_secret_value "$secret_id" "$value"
}

# ----------------------------------------------------------------------------
# Phase 5 — Build image and deploy
# ----------------------------------------------------------------------------
phase_5_image() {
  log_phase "Phase 5 — Build image and deploy"

  [[ -x "$BUILD_SCRIPT" ]] || die "Expected $BUILD_SCRIPT to be executable"

  ENVIRONMENT=production \
    CONFIG_MODE=image \
    CONFIG_PATH="$TFVARS_PATH" \
    "$BUILD_SCRIPT"

  log_info "Image built, pushed, and deployed"
}

# ----------------------------------------------------------------------------
# Phase 6 — DNS records
# ----------------------------------------------------------------------------

# Fetch the resourceRecords Cloud Run wants for a given domain.
cloud_run_records_json() {
  gcloud beta run domain-mappings describe \
    --domain "$1" \
    --region "$REGION" \
    --project "$PROJECT_ID" \
    --format=json 2> /dev/null || echo ''
}

print_manual_records_for() {
  local domain="$1"
  local json
  json=$(cloud_run_records_json "$domain")
  if [[ -z "$json" || "$json" == "null" ]]; then
    log_warn "No domain mapping found for $domain — Phase 3 may not have created it yet"
    return
  fi
  echo
  printf '%bDNS records for %s%b\n' "$BOLD" "$domain" "$NC"
  jq -r '.status.resourceRecords // [] | .[] | "  \(.type)  \(.name)  →  \(.rrdata)"' <<< "$json"
}

# Resolve the Cloudflare zone id whose name is a suffix of `$domain`.
cloudflare_zone_id_for() {
  local domain="$1"
  local zones
  zones=$(curl -fsS -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    "https://api.cloudflare.com/client/v4/zones?per_page=50" 2> /dev/null || echo '')
  [[ -z "$zones" ]] && {
    log_error "Failed to list Cloudflare zones (token or network)"
    return 1
  }
  jq -r --arg domain "$domain" '
        .result // [] |
        map(select($domain | endswith(.name))) |
        sort_by(.name | length) | reverse | .[0].id // empty
    ' <<< "$zones"
}

# Check if a record already exists at (zone, type, name). Echoes the record id.
cloudflare_find_record() {
  local zone_id="$1" rtype="$2" rname="$3"
  curl -fsS -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    "https://api.cloudflare.com/client/v4/zones/$zone_id/dns_records?type=$rtype&name=$rname" 2> /dev/null \
    | jq -r '.result // [] | .[0].id // empty'
}

# Upsert a single DNS record at Cloudflare. Returns 0 on success.
cloudflare_upsert_record() {
  local zone_id="$1" rtype="$2" rname="$3" rcontent="$4"
  # Strip any trailing dot Google returns for the rrdata.
  rcontent="${rcontent%.}"
  rname="${rname%.}"

  local existing
  existing=$(cloudflare_find_record "$zone_id" "$rtype" "$rname")
  local body
  body=$(jq -n \
    --arg type "$rtype" \
    --arg name "$rname" \
    --arg content "$rcontent" \
    '{type:$type, name:$name, content:$content, ttl:300, proxied:false}')

  local url method
  if [[ -n "$existing" ]]; then
    url="https://api.cloudflare.com/client/v4/zones/$zone_id/dns_records/$existing"
    method="PUT"
  else
    url="https://api.cloudflare.com/client/v4/zones/$zone_id/dns_records"
    method="POST"
  fi

  local resp
  resp=$(curl -fsS -X "$method" \
    -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    -H "Content-Type: application/json" \
    --data "$body" \
    "$url" 2> /dev/null || echo '')
  [[ -z "$resp" ]] && {
    log_error "Cloudflare $method failed for $rtype $rname"
    return 1
  }
  if [[ $(jq -r '.success // false' <<< "$resp") != "true" ]]; then
    log_error "Cloudflare rejected $rtype $rname → $rcontent"
    jq -r '.errors // [] | .[] | "  · \(.message)"' <<< "$resp" >&2
    return 1
  fi
  log_info "  · $rtype $rname → $rcontent  ($(echo "$method" | tr '[:upper:]' '[:lower:]'))"
}

# Return 0 if `dig NS` for the domain (or its ancestors) reports Cloudflare.
cloudflare_is_authoritative_for() {
  local probe="$1"
  command -v dig > /dev/null 2>&1 || return 0 # skip silently if dig absent
  while [[ "$probe" == *.* ]]; do
    local ns
    ns=$(dig +short NS "$probe" 2> /dev/null || true)
    if [[ -n "$ns" ]]; then
      grep -qi 'cloudflare\.com\.\?$' <<< "$ns" && return 0
      return 1
    fi
    probe="${probe#*.}"
  done
  return 1
}

publish_cloudflare_records_for() {
  local domain="$1"
  local json
  json=$(cloud_run_records_json "$domain")
  if [[ -z "$json" || "$json" == "null" ]]; then
    log_warn "No domain mapping found for $domain — Phase 3 may not have created it yet"
    return
  fi
  local zone_id
  zone_id=$(cloudflare_zone_id_for "$domain" || true)
  if [[ -z "$zone_id" ]]; then
    log_warn "No Cloudflare zone found for $domain — falling back to manual output"
    print_manual_records_for "$domain"
    return
  fi
  if ! cloudflare_is_authoritative_for "$domain"; then
    log_warn "$domain NS records do not currently delegate to Cloudflare."
    log_warn "Records will be written to Cloudflare zone $zone_id, but the public internet"
    log_warn "will keep resolving via whatever nameservers are live until NS records are updated."
  fi
  log_info "Publishing $domain records to Cloudflare zone $zone_id"
  local count
  count=$(jq '.status.resourceRecords // [] | length' <<< "$json")
  if [[ "$count" == "0" ]]; then
    log_warn "Cloud Run emitted zero records for $domain; nothing to publish"
    return
  fi
  while IFS=$'\t' read -r rtype rname rcontent; do
    cloudflare_upsert_record "$zone_id" "$rtype" "$rname" "$rcontent" || true
  done < <(jq -r '.status.resourceRecords // [] | .[] | [.type, .name, .rrdata] | @tsv' <<< "$json")
}

phase_6_dns() {
  log_phase "Phase 6 — DNS records"

  local targets=("$PRIMARY_DOMAIN")
  [[ -n "$SECONDARY_DOMAIN" ]] && targets+=("$SECONDARY_DOMAIN")

  case "$DNS_PROVIDER" in
    cloudflare)
      [[ -n "${CLOUDFLARE_API_TOKEN:-}" ]] || {
        if [[ "$NON_INTERACTIVE" == "true" ]]; then
          die "CLOUDFLARE_API_TOKEN env var is required for --dns-provider cloudflare"
        fi
        CLOUDFLARE_API_TOKEN=$(prompt_secret_value "Enter CLOUDFLARE_API_TOKEN (Zone:DNS:Edit)")
        export CLOUDFLARE_API_TOKEN
      }
      for domain in "${targets[@]}"; do
        publish_cloudflare_records_for "$domain"
      done
      ;;
    manual | "")
      for domain in "${targets[@]}"; do
        print_manual_records_for "$domain"
      done
      cat << EOF

Publish each record above at the respective DNS provider. Typical subdomain
mappings resolve via a single CNAME to ghs.googlehosted.com; apex domains
need four A and four AAAA records. SSL provisioning begins once DNS is live
and typically completes within 15–30 minutes.
EOF
      ;;
    *)
      die "Unsupported DNS provider: $DNS_PROVIDER (supported: cloudflare, manual)"
      ;;
  esac
}

# ----------------------------------------------------------------------------
# Phase 7 — Verify
# ----------------------------------------------------------------------------
wait_health() {
  local url="$1"
  local label="$2"
  local deadline=$((SECONDS + 30 * 60))
  local code

  while ((SECONDS < deadline)); do
    if code=$(curl -fsS -o /dev/null -w '%{http_code}' --max-time 10 "$url/health" 2> /dev/null); then
      if [[ "$code" == "200" ]]; then
        log_info "$label ($url) → 200 OK"
        return 0
      fi
    fi
    log_info "$label pending (last: ${code:-no response}); retrying in 60s"
    sleep 60
  done
  log_error "$label never returned 200 within 30 minutes"
  return 1
}

phase_7_verify() {
  log_phase "Phase 7 — Verify"

  local service_url
  service_url=$(gcloud run services describe logdate-server \
    --region "$REGION" \
    --project "$PROJECT_ID" \
    --format='value(status.url)')
  log_info "Cloud Run service URL: $service_url"

  wait_health "$service_url" "Cloud Run URL"
  wait_health "https://$PRIMARY_DOMAIN" "Primary domain"
  [[ -n "$SECONDARY_DOMAIN" ]] && wait_health "https://$SECONDARY_DOMAIN" "Secondary domain"

  echo
  log_info "Deployment verified."
  printf '  • %s/health → 200\n' "$service_url"
  printf '  • https://%s/health → 200\n' "$PRIMARY_DOMAIN"
  [[ -n "$SECONDARY_DOMAIN" ]] && printf '  • https://%s/health → 200\n' "$SECONDARY_DOMAIN"
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
main() {
  parse_args "$@"

  confirm_gcloud_identity
  detect_project_id
  detect_dns_provider

  log_info "Project: $PROJECT_ID"
  log_info "Region: $REGION"
  log_info "Primary domain: $PRIMARY_DOMAIN"
  log_info "Secondary domain: ${SECONDARY_DOMAIN:-<none>}"
  log_info "WebAuthn rpId: ${WEBAUTHN_RP_ID:-$PRIMARY_DOMAIN}"
  log_info "DNS provider: $DNS_PROVIDER"

  [[ "$SKIP_PREREQS" == "true" ]] || phase_0_prereqs
  [[ "$SKIP_DOMAIN_CHECK" == "true" ]] || phase_1_domain_check
  [[ "$SKIP_TFVARS" == "true" ]] || phase_2_tfvars
  [[ "$SKIP_TERRAFORM" == "true" ]] || phase_3a_infra
  [[ "$SKIP_SECRETS" == "true" ]] || phase_4_secrets
  [[ "$SKIP_TERRAFORM" == "true" ]] || phase_3b_cloud_run
  [[ "$SKIP_IMAGE" == "true" ]] || phase_5_image
  [[ "$SKIP_DNS" == "true" ]] || phase_6_dns
  [[ "$SKIP_VERIFY" == "true" ]] || phase_7_verify

  log_phase "Done"
}

main "$@"
