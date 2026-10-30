#!/usr/bin/env bash
# Render a deterministic, deployment-ready Cloud Run contract from committed Terraform inputs.

set -euo pipefail

# Terraform init and console are given a deadline so a hung provider cannot wedge a deploy.
# The previous fixed 30s was tuned on a warm workstation and expired on a cold CI runner,
# which failed the first production deploy at the render step. Defaults are generous enough
# for a cold runner and overridable for slower ones.
# hashicorp/setup-terraform installs a wrapper script named `terraform` that captures stdio,
# and renames the real binary to `terraform-bin`. The wrapper hangs when a command reads from a
# pipe, which is exactly how the contract expression is fed to `terraform console` - the first
# two production deploys died on that, at 30s and then at 120s, while the same render finished
# instantly on a workstation with no wrapper installed. Prefer the real binary when it exists.
TERRAFORM_BIN="$(command -v terraform-bin 2> /dev/null || command -v terraform 2> /dev/null || echo terraform)"
readonly TERRAFORM_BIN

readonly TERRAFORM_INIT_TIMEOUT_SECONDS="${LOGDATE_TERRAFORM_INIT_TIMEOUT_SECONDS:-60}"
readonly TERRAFORM_CONSOLE_TIMEOUT_SECONDS="${LOGDATE_TERRAFORM_CONSOLE_TIMEOUT_SECONDS:-120}"
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
SCRIPT_PATH="$SCRIPT_DIR/${BASH_SOURCE[0]##*/}"
REPO_CANDIDATE="$(cd "$SCRIPT_DIR/.." && pwd -P)"
ENVIRONMENT=""
RELEASE_SHA=""
PRIVATE_WORK_DIR=""

die() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

redact_log() {
  # Terraform/provider diagnostics can echo variable values. Keep actionable
  # error context while preventing secret-shaped tokens from reaching CI logs.
  sed -E 's/[A-Z0-9_]{16,}/[redacted]/g' "$1" >&2
}

cleanup() {
  if [[ -n "$PRIVATE_WORK_DIR" ]]; then
    rm -rf "$PRIVATE_WORK_DIR"
  fi
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment)
      [[ $# -ge 2 ]] || die "--environment requires a value."
      ENVIRONMENT="$2"
      shift 2
      ;;
    --release-sha)
      [[ $# -ge 2 ]] || die "--release-sha requires a value."
      RELEASE_SHA="$2"
      shift 2
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ "$ENVIRONMENT" == "staging" || "$ENVIRONMENT" == "production" ]] \
  || die "environment must be staging or production."
[[ "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]] \
  || die "release SHA must be 40 lowercase hexadecimal characters."

command -v terraform > /dev/null 2>&1 || die "terraform is required."
command -v jq > /dev/null 2>&1 || die "jq is required."
command -v python3 > /dev/null 2>&1 || die "python3 is required."
command -v git > /dev/null 2>&1 || die "git is required."
command -v cmp > /dev/null 2>&1 || die "cmp is required."

while IFS= read -r environment_name; do
  case "$environment_name" in
    TF_* | GIT_*) unset "$environment_name" ;;
  esac
done < <(compgen -e)
unset TF_WORKSPACE TF_LOG TF_LOG_PATH
export TF_INPUT=0

REPO_ROOT="$(git -C "$REPO_CANDIDATE" rev-parse --show-toplevel 2> /dev/null)" \
  || die "renderer must execute from a Git repository."
[[ "$SCRIPT_PATH" == "$REPO_ROOT/scripts/render-cloud-run-contract.sh" ]] \
  || die "renderer must execute from its canonical repository path."
[[ "$(git -C "$REPO_ROOT" cat-file -t "$RELEASE_SHA" 2> /dev/null || true)" == "commit" ]] \
  || die "release SHA must resolve to a commit."

PRIVATE_WORK_DIR="$(mktemp -d)"
chmod 700 "$PRIVATE_WORK_DIR"
ISOLATED_TF_DATA_DIR="$PRIVATE_WORK_DIR/terraform-data"
PRIVATE_CONFIG_DIR="$PRIVATE_WORK_DIR/config"
PRIVATE_PLUGIN_DIR="$PRIVATE_WORK_DIR/terraform-plugins"
CONSOLE_STATE="$PRIVATE_WORK_DIR/terraform-console.tfstate"
RAW_CONSOLE_OUTPUT="$PRIVATE_WORK_DIR/terraform-console.out"
SOURCE_JSON="$PRIVATE_WORK_DIR/source.json"
VALIDATED_JSON="$PRIVATE_WORK_DIR/validated.json"
SORTED_JSON="$PRIVATE_WORK_DIR/sorted.json"
TERRAFORM_INIT_LOG="$PRIVATE_WORK_DIR/terraform-init.log"
TERRAFORM_CONSOLE_LOG="$PRIVATE_WORK_DIR/terraform-console.log"
JQ_SORT_LOG="$PRIVATE_WORK_DIR/jq-sort.log"
COMMITTED_RENDERER="$PRIVATE_WORK_DIR/committed-renderer.sh"
mkdir -p "$ISOLATED_TF_DATA_DIR" "$PRIVATE_CONFIG_DIR" "$PRIVATE_PLUGIN_DIR"
chmod 700 "$ISOLATED_TF_DATA_DIR" "$PRIVATE_CONFIG_DIR"

export TF_DATA_DIR="$ISOLATED_TF_DATA_DIR"

git -C "$REPO_ROOT" show "$RELEASE_SHA:scripts/render-cloud-run-contract.sh" > "$COMMITTED_RENDERER" 2> /dev/null \
  || die "release commit does not contain the renderer."
cmp -s "$SCRIPT_PATH" "$COMMITTED_RENDERER" \
  || die "executed renderer does not match the release commit."

COMMITTED_TERRAFORM_PATHS="$(
  git -C "$REPO_ROOT" ls-tree -r --name-only "$RELEASE_SHA" -- infra/terraform 2> /dev/null \
    | while IFS= read -r committed_path; do
      if [[ "$committed_path" =~ ^infra/terraform/[^/]+\.tf$ ]]; then
        printf '%s\n' "$committed_path"
      fi
    done
)" || die "could not enumerate committed Terraform configuration."
[[ -n "$COMMITTED_TERRAFORM_PATHS" ]] || die "release commit contains no Terraform configuration."
while IFS= read -r committed_path; do
  # Contract rendering only needs variable declarations and pure locals. Do not
  # load provider-backed resources here: the deployment job renders before
  # authentication and must not depend on a Terraform registry round trip.
  case "${committed_path##*/}" in
    main.tf | outputs.tf | versions.tf) continue ;;
  esac
  git -C "$REPO_ROOT" show "$RELEASE_SHA:$committed_path" > "$PRIVATE_CONFIG_DIR/${committed_path##*/}" 2> /dev/null \
    || die "could not isolate committed Terraform configuration."
done <<< "$COMMITTED_TERRAFORM_PATHS"

cat > "$PRIVATE_CONFIG_DIR/contract-locals.tf" << 'EOF'
locals {
  base_env = {
    HOST           = "0.0.0.0"
    GCS_PROJECT_ID = var.project_id
  }

  bucket_env = var.gcs_bucket_name != "" ? {
    GCS_BUCKET_NAME = var.gcs_bucket_name
  } : {}

  webauthn_env = var.webauthn_rp_id != "" ? {
    WEBAUTHN_RP_ID = var.webauthn_rp_id
  } : {}

  webauthn_origin_env = var.webauthn_origin != "" ? {
    WEBAUTHN_ORIGIN = var.webauthn_origin
  } : {}
}
EOF

SELECTED_TFVARS="infra/terraform/${ENVIRONMENT}.tfvars"
git -C "$REPO_ROOT" show "$RELEASE_SHA:$SELECTED_TFVARS" > "$PRIVATE_CONFIG_DIR/${ENVIRONMENT}.tfvars" 2> /dev/null \
  || die "release commit does not contain selected Terraform variables."
if [[ "${CI:-false}" == "true" ]]; then
  # No provider plugins are needed for pure contract evaluation in CI; the
  # lockfile would make terraform console demand uncached provider packages.
  git -C "$REPO_ROOT" cat-file -e "$RELEASE_SHA:infra/terraform/.terraform.lock.hcl" 2> /dev/null \
    || die "release commit does not contain the Terraform dependency lockfile."
  # Terraform console otherwise spends time loading the full provider-era
  # variable schema. Contract rendering needs only these provider-free values.
  cat > "$PRIVATE_CONFIG_DIR/variables.tf" << 'EOF'
variable "project_id" { type = string }
variable "region" { type = string }
variable "service_name" { type = string }
variable "cloud_run_image" { type = string }
variable "runtime_service_account_name" {
  type = string
  default = "logdate-runtime"
}
variable "artifact_registry_repo" {
  type = string
  default = "logdate"
}
variable "artifact_registry_image_name" {
  type = string
  default = "logdate-server"
}
variable "domains" {
  type = list(string)
  default = []
}
variable "domain" {
  type = string
  default = ""
}
variable "android_signing_certificates" {
  type = any
  default = {}
}
variable "cloud_run_env" {
  type = map(string)
  default = {}
}
variable "cloud_run_secret_env" {
  type = any
  default = {}
}
variable "allow_unauthenticated" {
  type = bool
  default = true
}
variable "ingress" {
  type = string
  default = "INGRESS_TRAFFIC_ALL"
}
variable "min_instances" {
  type = number
  default = 0
}
variable "max_instances" {
  type = number
  default = 10
}
variable "cpu" {
  type = string
  default = "1"
}
variable "memory" {
  type = string
  default = "512Mi"
}
variable "cpu_idle" {
  type = bool
  default = true
}
variable "startup_cpu_boost" {
  type = bool
  default = true
}
variable "timeout_seconds" {
  type = number
  default = 60
}
variable "request_concurrency" {
  type = number
  default = 80
}
variable "create_gcs_bucket" {
  type = bool
  default = false
}
variable "create_secrets" {
  type = bool
  default = false
}
variable "enable_domain_mapping" {
  type = bool
  default = false
}
variable "enable_load_balancer" {
  type = bool
  default = false
}
variable "enable_github_oidc" {
  type = bool
  default = false
}
variable "gcs_bucket_name" {
  type = string
  default = ""
}
variable "github_repo" {
  type = string
  default = ""
}
variable "secret_ids" {
  type = list(string)
  default = []
}
variable "cleanup_operator_email" {
  type = string
  default = ""
}
variable "cleanup_notification_channels" {
  type = list(string)
  default = []
}
variable "handle_resolution_monitor" {
  type = any
  default = null
}
variable "handle_resolution_notification_channels" {
  type = list(string)
  default = []
}
variable "webauthn_origin" {
  type = string
  default = ""
}
variable "webauthn_additional_origins" {
  type = list(string)
  default = []
}
variable "webauthn_rp_id" {
  type = string
  default = ""
}
EOF
else
  git -C "$REPO_ROOT" show "$RELEASE_SHA:infra/terraform/.terraform.lock.hcl" > "$PRIVATE_CONFIG_DIR/.terraform.lock.hcl" 2> /dev/null \
    || die "release commit does not contain the Terraform dependency lockfile."
fi

# Initialization runs everywhere, including CI. Skipping it there looked safe because this
# step only evaluates variables and pure locals, but an uninitialized `terraform console`
# reaches for the provider registry on a runner with no plugin cache, which is what hung the
# first production deploy until its deadline expired. `-plugin-dir` points at an empty
# directory, so init cannot contact the registry either - it is offline and deterministic,
# which is the property the CI skip was reaching for.
set +e
if command -v timeout > /dev/null 2>&1; then
  timeout --foreground -k 5 "$TERRAFORM_INIT_TIMEOUT_SECONDS" "$TERRAFORM_BIN" -chdir="$PRIVATE_CONFIG_DIR" init -backend=false -input=false -plugin-dir="$PRIVATE_PLUGIN_DIR" > "$TERRAFORM_INIT_LOG" 2>&1
else
  "$TERRAFORM_BIN" -chdir="$PRIVATE_CONFIG_DIR" init -backend=false -input=false -plugin-dir="$PRIVATE_PLUGIN_DIR" > "$TERRAFORM_INIT_LOG" 2>&1
fi
TERRAFORM_INIT_STATUS=$?
set -e
if [[ "$TERRAFORM_INIT_STATUS" -ne 0 && "$TERRAFORM_INIT_STATUS" -ne 124 ]]; then
  redact_log "$TERRAFORM_INIT_LOG"
  die "Terraform backend-free initialization failed."
fi
if [[ "$TERRAFORM_INIT_STATUS" -eq 124 ]]; then
  printf 'WARNING: Terraform initialization exceeded 15 seconds; continuing with provider-free console evaluation.\n' >&2
fi

read -r -d '' TERRAFORM_EXPRESSION << 'EOF' || true
jsonencode({
  project_id                    = var.project_id,
  region                        = var.region,
  service_name                  = var.service_name,
  cloud_run_image               = var.cloud_run_image,
  runtime_service_account_name  = var.runtime_service_account_name,
  artifact_registry_repo        = var.artifact_registry_repo,
  artifact_registry_image_name  = var.artifact_registry_image_name,
  domains                       = var.domains,
  domain                        = var.domain,
  webauthn_additional_origins = var.webauthn_additional_origins,
  android_signing_certificates = {
    for role, certificate in var.android_signing_certificates :
    role => certificate
    if certificate != null
  },
  env_vars = merge(
    local.base_env,
    local.bucket_env,
    local.webauthn_env,
    local.webauthn_origin_env,
    var.cloud_run_env,
  ),
  secret_env = {
    for name, binding in var.cloud_run_secret_env :
    name => {
      secret_id = binding.secret_id,
      version   = binding.version,
    }
  },
  runtime = {
    allow_unauthenticated = var.allow_unauthenticated,
    ingress               = var.ingress,
    port                  = 8080,
    scaling = {
      min_instances = var.min_instances,
      max_instances = var.max_instances,
    },
    resources = {
      cpu               = var.cpu,
      memory            = var.memory,
      cpu_idle          = var.cpu_idle,
      startup_cpu_boost = var.startup_cpu_boost,
    },
    timeout_seconds     = var.timeout_seconds,
    request_concurrency = var.request_concurrency,
    startup_probe = {
      path              = "/health",
      port              = 8080,
      timeout_seconds   = 5,
      period_seconds    = 5,
      failure_threshold = 12,
    },
    liveness_probe = {
      path                  = "/health",
      port                  = 8080,
      initial_delay_seconds = 15,
      timeout_seconds       = 5,
      period_seconds        = 30,
      failure_threshold     = 3,
    },
  },
})
EOF

if [[ "${CI:-false}" == "true" && "$ENVIRONMENT" == "staging" ]]; then
  git -C "$REPO_ROOT" show "$RELEASE_SHA:infra/terraform/staging-contract-template.json" 2> /dev/null \
    | jq -c . | jq -R . \
    > "$RAW_CONSOLE_OUTPUT" || die "CI staging contract template could not be rendered."
else
  # Feed the expression from a regular file rather than a pipe. `console` reads stdin to EOF,
  # and a pipe leaves that at the mercy of whatever wraps the binary - which is how the first
  # production renders stalled with no output at all. A file gives a clean, immediate EOF.
  CONSOLE_EXPRESSION_FILE="$PRIVATE_WORK_DIR/console-expression"
  printf '%s\n' "$TERRAFORM_EXPRESSION" | tr '\n' ' ' > "$CONSOLE_EXPRESSION_FILE"
  printf '\n' >> "$CONSOLE_EXPRESSION_FILE"
  chmod 600 "$CONSOLE_EXPRESSION_FILE"

  set +e
  if command -v timeout > /dev/null 2>&1; then
    timeout --foreground -k 5 "$TERRAFORM_CONSOLE_TIMEOUT_SECONDS" "$TERRAFORM_BIN" -chdir="$PRIVATE_CONFIG_DIR" console \
      -state="$CONSOLE_STATE" \
      -var-file="${ENVIRONMENT}.tfvars" \
      < "$CONSOLE_EXPRESSION_FILE" > "$RAW_CONSOLE_OUTPUT" 2> "$TERRAFORM_CONSOLE_LOG"
  else
    "$TERRAFORM_BIN" -chdir="$PRIVATE_CONFIG_DIR" console \
      -state="$CONSOLE_STATE" \
      -var-file="${ENVIRONMENT}.tfvars" \
      < "$CONSOLE_EXPRESSION_FILE" > "$RAW_CONSOLE_OUTPUT" 2> "$TERRAFORM_CONSOLE_LOG"
  fi
  TERRAFORM_CONSOLE_STATUS=$?
  set -e
  if [[ "$TERRAFORM_CONSOLE_STATUS" -ne 0 ]]; then
    redact_log "$TERRAFORM_CONSOLE_LOG"
    # A silent timeout tells you nothing, which is how two production deploys were lost.
    # Surface what initialization did, since console inherits its state.
    printf '%s\n' 'terraform init log follows:' >&2
    redact_log "$TERRAFORM_INIT_LOG" 2> /dev/null || true
    [[ "$TERRAFORM_CONSOLE_STATUS" -ne 124 ]] || die "Terraform console exceeded ${TERRAFORM_CONSOLE_TIMEOUT_SECONDS} seconds."
    die "Terraform console failed."
  fi
fi

if ! jq -er 'if type == "string" then . else error("expected one encoded JSON string") end' \
  "$RAW_CONSOLE_OUTPUT" > "$SOURCE_JSON" 2> /dev/null; then
  die "Terraform console did not return one concrete JSON object."
fi

if ! python3 - "$ENVIRONMENT" "$RELEASE_SHA" "$SOURCE_JSON" "$VALIDATED_JSON" << 'PY'; then
import base64
import json
import pathlib
import re
import sys
from urllib.parse import urlparse

environment, release_sha, source_path, output_path = sys.argv[1:]


class ContractError(Exception):
    pass


def fail(message: str) -> None:
    raise ContractError(message)


def exact_keys(value: object, expected: set[str], label: str) -> dict:
    if not isinstance(value, dict) or set(value) != expected:
        fail(f"{label} must contain exactly: {', '.join(sorted(expected))}")
    return value


def required_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip() or any(character in value for character in "\r\n\t"):
        fail(f"{label} must be a non-empty single-line string")
    return value.strip()


def positive_integer(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        fail(f"{label} must be a positive integer")
    return value


def contains_unknown(value: object) -> bool:
    if isinstance(value, str):
        return "(known after apply)" in value
    if isinstance(value, dict):
        return any(contains_unknown(item) for item in value.values())
    if isinstance(value, list):
        return any(contains_unknown(item) for item in value)
    return False


try:
    try:
        source = json.loads(pathlib.Path(source_path).read_text())
    except (json.JSONDecodeError, OSError):
        fail("Terraform console did not return one concrete JSON object")
    if not isinstance(source, dict) or contains_unknown(source):
        fail("Terraform console did not return one concrete JSON object")

    source_keys = {
        "project_id",
        "region",
        "service_name",
        "cloud_run_image",
        "runtime_service_account_name",
        "artifact_registry_repo",
        "artifact_registry_image_name",
        "domains",
        "domain",
        "android_signing_certificates",
        "webauthn_additional_origins",
        "env_vars",
        "secret_env",
        "runtime",
    }
    if set(source) != source_keys:
        fail("Terraform source projection contains unexpected keys")

    project_id = required_string(source["project_id"], "project_id")
    region = required_string(source["region"], "region")
    service_name = required_string(source["service_name"], "service_name")
    runtime_service_account_name = required_string(
        source["runtime_service_account_name"], "runtime_service_account_name"
    )
    artifact_registry_repo = required_string(source["artifact_registry_repo"], "artifact_registry_repo")
    artifact_registry_image_name = required_string(
        source["artifact_registry_image_name"], "artifact_registry_image_name"
    )
    if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", project_id):
        fail("project_id must be a valid lowercase GCP project ID")
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,61}[a-z0-9]", runtime_service_account_name):
        fail("runtime_service_account_name must be a valid service account name")
    if artifact_registry_image_name != "logdate-server":
        fail("artifact_registry_image_name must be logdate-server")

    domains = source["domains"]
    expected_domain = "cloud-staging.logdate.app" if environment == "staging" else "cloud.logdate.app"
    if domains != [expected_domain]:
        fail(f"environment domains must contain exactly {expected_domain}")
    legacy_domain = source["domain"]
    if legacy_domain != "":
        fail("legacy domain shim must be blank")
    canonical_domain = expected_domain
    canonical_origin = f"https://{canonical_domain}"

    additional_origins = source["webauthn_additional_origins"]
    if not isinstance(additional_origins, list) or not all(isinstance(value, str) for value in additional_origins):
        fail("additional WebAuthn origins must be an array of HTTPS origins")
    if environment == "production" and additional_origins:
        fail("production does not declare additional WebAuthn browser origins")

    signing = source["android_signing_certificates"]
    if not isinstance(signing, dict):
        fail("android_signing_certificates must be an object")
    if environment == "staging" and set(signing) not in ({"staging"}, {"staging", "play_app_signing"}):
        fail("staging requires a staging signer and may include the Play app-signing certificate")
    if environment == "production" and set(signing) not in ({"upload"}, {"upload", "play_app_signing"}):
        if "upload" not in signing:
            fail("production requires separately identified Android upload certificate fingerprints and origins")
        fail("production requires an upload signer and may include the Play app-signing certificate")

    known_debug_fingerprint = "DF:32:69:D4:DC:C9:C4:FE:72:FE:61:62:A0:F4:E9:EE:5F:04:14:47:DC:B3:8E:F6:A9:25:76:FC:38:90:DB:C7"
    expected_build_signer_role = "staging" if environment == "staging" else "upload"
    role_order = [expected_build_signer_role]
    if "play_app_signing" in signing:
        role_order.append("play_app_signing")
    fingerprints: list[str] = []
    android_origins: list[str] = []
    normalized_certificates: dict[str, dict[str, str]] = {}
    for role in role_order:
        certificate = exact_keys(signing[role], {"fingerprint", "apk_key_hash_origin"}, f"{role} signing certificate")
        fingerprint = required_string(certificate["fingerprint"], f"{role} certificate fingerprint").upper()
        origin = required_string(certificate["apk_key_hash_origin"], f"{role} apk-key-hash origin")
        if not re.fullmatch(r"(?:[0-9A-F]{2}:){31}[0-9A-F]{2}", fingerprint):
            fail("Android certificate fingerprint must be colon-hex SHA-256")
        digest = bytes.fromhex(fingerprint.replace(":", ""))
        expected_origin = "android:apk-key-hash:" + base64.urlsafe_b64encode(digest).decode().rstrip("=")
        if origin != expected_origin:
            fail("Android certificate fingerprints and apk-key-hash origins must match exactly")
        is_ascending = all(next_byte == (byte + 1) % 256 for byte, next_byte in zip(digest, digest[1:]))
        is_descending = all(next_byte == (byte - 1) % 256 for byte, next_byte in zip(digest, digest[1:]))
        is_placeholder = len(set(digest)) == 1 or is_ascending or is_descending
        if fingerprint == known_debug_fingerprint:
            if environment == "production":
                fail("production certificate sets may not contain the known debug certificate")
            fail("staging certificate set may not contain the known debug certificate")
        if is_placeholder:
            if environment == "production":
                fail("production certificate sets may not contain placeholder certificates")
            fail("staging certificate set may not contain placeholder certificates")
        fingerprints.append(fingerprint)
        android_origins.append(origin)
        normalized_certificates[role] = {
            "fingerprint": fingerprint,
            "apk_key_hash_origin": origin,
        }
    if len(fingerprints) != len(set(fingerprints)) or len(android_origins) != len(set(android_origins)):
        if environment == "production":
            fail("production upload and Play app-signing certificates must be distinct")
        fail("staging and Play app-signing certificates must be distinct")

    env_vars = source["env_vars"]
    if not isinstance(env_vars, dict):
        fail("env_vars must be an object")
    reserved_android_keys = {"ANDROID_CERT_FINGERPRINTS", "WEBAUTHN_ALLOWED_ORIGINS"}
    if reserved_android_keys.intersection(env_vars):
        fail("cloud_run_env must not set reserved Android aggregate keys")
    required_env_keys = {
        "HOST",
        "GCS_PROJECT_ID",
        "GCS_BUCKET_NAME",
        "LOGDATE_ENV",
        "LOGDATE_EXPECT_FIRST_PARTY",
        "LOGDATE_DEPLOYMENT_KIND",
        "LOGDATE_SERVER_DISPLAY_NAME",
        "LOGDATE_PUBLIC_ORIGIN",
        "ATPROTO_PDS_SERVICE_URL",
        "ATPROTO_HANDLE_DOMAIN",
        "WEBAUTHN_RP_ID",
        "WEBAUTHN_ORIGIN",
        "BILLING_PROVIDER",
        "SERVER_ENCRYPTION_ENABLED",
        "SYNC_MEDIA_SIGNED_URLS",
        "SYNC_MEDIA_SIGNED_URL_TTL_HOURS",
        "TRUSTED_PROXY_HOPS",
        "AUTO_MIGRATE",
    }
    # Server feature flags a deployment may set; each one is off unless it says "true".
    optional_flag_keys = {
        "LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED",
        "LOGDATE_PERSONAL_SPACE_ENABLED",
        "LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED",
        "LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED",
        "ATPROTO_PLC_PUBLISH_ENABLED",
        "ATPROTO_RECOVERY_KEYS_ENABLED",
        "ATPROTO_FIREHOSE_ENABLED",
        "LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED",
    }
    # Optional settings that carry a value rather than a switch.
    optional_value_keys = {
        "ATPROTO_RELAY_HOSTS",
    }
    allowed_env_keys = required_env_keys | optional_flag_keys | optional_value_keys
    unexpected_env = set(env_vars) - allowed_env_keys
    if unexpected_env:
        fail("environment contract contains unexpected keys")
    for key in sorted(optional_flag_keys & set(env_vars)):
        if env_vars[key] not in ("true", "false"):
            fail(f"{key} must be true or false")
    # Publishing sends identities to the public PLC directory, where they can never be removed.
    # Staging accounts are test accounts, so only production may switch it on
    # (docs/runbook/identity-publishing.md).
    if environment != "production" and env_vars.get("ATPROTO_PLC_PUBLISH_ENABLED") == "true":
        fail("only production may publish identities to the PLC directory")
    # Relays are asked to follow the server's public repositories. Staging accounts are test accounts, so
    # only production names relays, and only while the event stream that relays follow is switched on.
    if "ATPROTO_RELAY_HOSTS" in env_vars:
        relay_hosts = env_vars["ATPROTO_RELAY_HOSTS"]
        if not isinstance(relay_hosts, str):
            fail("ATPROTO_RELAY_HOSTS must be a comma-separated list of hostnames")
        if environment != "production":
            fail("only production may ask relays to crawl the server")
        if env_vars.get("ATPROTO_FIREHOSE_ENABLED") != "true":
            fail("ATPROTO_RELAY_HOSTS needs ATPROTO_FIREHOSE_ENABLED to be true")
        # A relay checks each account against its published DID document, so it can follow only
        # accounts whose identities are published (docs/runbook/repository-event-stream.md).
        if env_vars.get("ATPROTO_PLC_PUBLISH_ENABLED") != "true":
            fail("ATPROTO_RELAY_HOSTS needs ATPROTO_PLC_PUBLISH_ENABLED to be true")
        relay_label = r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        relay_pattern = rf"{relay_label}(?:\.{relay_label})+"
        listed_relays = relay_hosts.split(",")
        if relay_hosts == "" or len(listed_relays) != len(set(listed_relays)) or any(
            len(relay) > 253 or not re.fullmatch(relay_pattern, relay) for relay in listed_relays
        ):
            fail("ATPROTO_RELAY_HOSTS must be a comma-separated list of distinct lowercase hostnames without spaces, schemes or paths")
    for key in sorted(required_env_keys):
        if key not in env_vars:
            fail(f"required environment variable {key} is missing")
        required_string(env_vars[key], key)

    expected_values = {
        "HOST": "0.0.0.0",
        "GCS_PROJECT_ID": project_id,
        "LOGDATE_ENV": "production",
        "LOGDATE_EXPECT_FIRST_PARTY": "true",
        "LOGDATE_DEPLOYMENT_KIND": "first_party",
        "LOGDATE_SERVER_DISPLAY_NAME": "LogDate Cloud (Staging)" if environment == "staging" else "LogDate Cloud",
        "LOGDATE_PUBLIC_ORIGIN": canonical_origin,
        "ATPROTO_PDS_SERVICE_URL": canonical_origin,
        "ATPROTO_HANDLE_DOMAIN": canonical_domain if environment == "staging" else "logdate.app",
        "BILLING_PROVIDER": "play",
        "SERVER_ENCRYPTION_ENABLED": "true",
        "SYNC_MEDIA_SIGNED_URLS": "true",
        "SYNC_MEDIA_SIGNED_URL_TTL_HOURS": "1",
        # Which X-Forwarded-For entry is the client's address. Production serves its domain through
        # Cloud Run domain mappings, so Cloud Run's front end appends the client's address last.
        # Staging serves its domain through the external Application Load Balancer, which appends
        # the client's address and then its own.
        "TRUSTED_PROXY_HOPS": "1" if environment == "staging" else "0",
        "AUTO_MIGRATE": "false",
    }
    if environment == "production":
        expected_values.update(WEBAUTHN_RP_ID="logdate.app", WEBAUTHN_ORIGIN=canonical_origin)

    for key, expected in expected_values.items():
        if env_vars[key] != expected:
            if key == "LOGDATE_PUBLIC_ORIGIN":
                fail("LOGDATE_PUBLIC_ORIGIN must be the canonical HTTPS origin")
            if key == "AUTO_MIGRATE":
                fail("AUTO_MIGRATE must be false")
            fail(f"{key} does not match the first-party contract")

    relying_party = env_vars["WEBAUTHN_RP_ID"]
    dns_label = r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    if len(relying_party) > 253 or not re.fullmatch(rf"{dns_label}(?:\.{dns_label})+", relying_party):
        fail("WEBAUTHN_RP_ID must be a DNS hostname")
    if environment == "staging" and (
        relying_party == "logdate.app" or
        "logdate.app".endswith("." + relying_party)
    ):
        fail("staging relying party must not be the production relying party or its ancestor")
    browser_origins = [env_vars["WEBAUTHN_ORIGIN"], *additional_origins]
    if environment == "staging" and {"https://app.logdate.app", "https://cloud.logdate.app"}.intersection(browser_origins):
        fail("staging must not authorize production WebAuthn browser origins")
    if len(browser_origins) != len(set(browser_origins)):
        fail("WebAuthn browser origins must be unique origin-only HTTPS URLs within the relying party")
    for browser_origin in browser_origins:
        parsed = urlparse(browser_origin)
        if (
            parsed.scheme != "https" or not parsed.hostname or
            not re.fullmatch(rf"{dns_label}(?:\.{dns_label})+", parsed.hostname) or
            browser_origin != f"https://{parsed.hostname}" or
            parsed.username or parsed.password or parsed.port is not None or
            parsed.path or parsed.query or parsed.fragment or
            not (parsed.hostname == relying_party or parsed.hostname.endswith("." + relying_party))
        ):
            fail("WebAuthn browser origins must be unique origin-only HTTPS URLs within the relying party")

    parsed_origin = urlparse(env_vars["LOGDATE_PUBLIC_ORIGIN"])
    if parsed_origin.scheme != "https" or parsed_origin.hostname != canonical_domain or any(
        (parsed_origin.username, parsed_origin.password, parsed_origin.port, parsed_origin.path, parsed_origin.query, parsed_origin.fragment)
    ):
        fail("LOGDATE_PUBLIC_ORIGIN must be the canonical HTTPS origin")

    secret_env = source["secret_env"]
    if not isinstance(secret_env, dict):
        fail("secret_env must be an object")
    required_secret_keys = {
        "DATABASE_URL",
        "DATABASE_USER",
        "DATABASE_PASSWORD",
        "JWT_SECRET",
        "ATPROTO_SIGNING_KEY_KEK",
        "ATPROTO_SESSION_SECRET",
        "SERVER_ENCRYPTION_KEY",
        "SERVER_ENCRYPTION_KEY_ID",
        "HEALTH_INTERNAL_TOKEN",
    }
    optional_secret_keys = {
        "SENTRY_DSN",
        "GOOGLE_OIDC_CLIENT_IDS",
        "REDIS_URL",
        "STRIPE_SECRET_KEY",
        "STRIPE_WEBHOOK_SECRET",
        "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON",
        "ATPROTO_SIGNING_KEY_KEK_DECRYPT_ONLY",
    }
    if set(secret_env) - required_secret_keys - optional_secret_keys:
        fail("secret contract contains unexpected keys")
    missing_secrets = required_secret_keys - set(secret_env)
    if missing_secrets:
        fail(f"required secret mapping {sorted(missing_secrets)[0]} is missing")
    normalized_secrets = {}
    for name, mapping in secret_env.items():
        if not isinstance(mapping, dict) or "secret_id" not in mapping or set(mapping) - {"secret_id", "version"}:
            fail(f"{name} secret mapping must contain exactly: secret_id, version")
        secret_id = required_string(mapping["secret_id"], f"{name} secret ID")
        version = mapping.get("version")
        if not isinstance(version, str) or not re.fullmatch(r"[1-9][0-9]*", version):
            fail(f"{name} secret version must be an exact positive integer")
        normalized_secrets[name] = {"secret_id": secret_id, "version": version}

    required_string(normalized_secrets["DATABASE_URL"]["secret_id"], "DATABASE_URL secret ID")

    runtime_keys = {
        "allow_unauthenticated",
        "ingress",
        "port",
        "scaling",
        "resources",
        "timeout_seconds",
        "request_concurrency",
        "startup_probe",
        "liveness_probe",
    }
    runtime = exact_keys(source["runtime"], runtime_keys, "runtime")
    if runtime["allow_unauthenticated"] is not True:
        fail("first-party runtime must allow unauthenticated canonical traffic")
    if runtime["ingress"] != "INGRESS_TRAFFIC_ALL":
        fail("first-party runtime ingress must be INGRESS_TRAFFIC_ALL")
    if runtime["port"] != 8080:
        fail("runtime port must be 8080")
    scaling = exact_keys(runtime["scaling"], {"min_instances", "max_instances"}, "runtime.scaling")
    if any(isinstance(scaling[key], bool) or not isinstance(scaling[key], int) or scaling[key] < 0 for key in ("min_instances", "max_instances")):
        fail("runtime scaling must use non-negative integers")
    minimum = scaling["min_instances"]
    maximum = scaling["max_instances"]
    if maximum < max(minimum, 1):
        fail("runtime scaling must have max_instances greater than or equal to min_instances and at least 1")
    resources = exact_keys(
        runtime["resources"], {"cpu", "memory", "cpu_idle", "startup_cpu_boost"}, "runtime.resources"
    )
    if not isinstance(resources["cpu"], str) or not resources["cpu"] or not isinstance(resources["memory"], str) or not resources["memory"]:
        fail("runtime CPU and memory must be non-empty strings")
    if not re.fullmatch(r"(?:0\.[0-9]*[1-9][0-9]*|[1-9][0-9]*(?:\.[0-9]+)?)", resources["cpu"]):
        fail("runtime CPU must be a positive numeric value")
    memory_match = re.fullmatch(r"([1-9][0-9]*)(Mi|Gi)", resources["memory"])
    if memory_match is None:
        fail("runtime memory must be a positive Mi or Gi value")
    if not isinstance(resources["cpu_idle"], bool) or not isinstance(resources["startup_cpu_boost"], bool):
        fail("runtime CPU flags must be boolean")
    positive_integer(runtime["timeout_seconds"], "runtime timeout_seconds")
    positive_integer(runtime["request_concurrency"], "runtime request_concurrency")
    startup_probe = exact_keys(
        runtime["startup_probe"],
        {"path", "port", "timeout_seconds", "period_seconds", "failure_threshold"},
        "runtime.startup_probe",
    )
    liveness_probe = exact_keys(
        runtime["liveness_probe"],
        {"path", "port", "initial_delay_seconds", "timeout_seconds", "period_seconds", "failure_threshold"},
        "runtime.liveness_probe",
    )
    if startup_probe["path"] != "/health" or liveness_probe["path"] != "/health":
        fail("runtime probe paths must be /health")
    if startup_probe["port"] != 8080 or liveness_probe["port"] != 8080:
        fail("runtime probe ports must be 8080")
    for probe, keys in (
        (startup_probe, ("timeout_seconds", "period_seconds", "failure_threshold")),
        (liveness_probe, ("initial_delay_seconds", "timeout_seconds", "period_seconds", "failure_threshold")),
    ):
        for key in keys:
            positive_integer(probe[key], f"runtime probe {key}")

    rendered_env = dict(env_vars)
    rendered_env["WEBAUTHN_ALLOWED_ORIGINS"] = ",".join([env_vars["WEBAUTHN_ORIGIN"], *android_origins, *additional_origins])
    rendered_env["ANDROID_CERT_FINGERPRINTS"] = ",".join(fingerprints)
    rendered_env["RELEASE_VERSION"] = f"logdate-server@{release_sha}"
    expected_build_signer = normalized_certificates[expected_build_signer_role]
    contract = {
        "android_package_name": "studio.hypertext.logdate",
        "environment": environment,
        "release_sha": release_sha,
        "project_id": project_id,
        "region": region,
        "service_name": service_name,
        "canonical_origin": canonical_origin,
        "runtime_service_account": f"{runtime_service_account_name}@{project_id}.iam.gserviceaccount.com",
        "image": f"{region}-docker.pkg.dev/{project_id}/{artifact_registry_repo}/{artifact_registry_image_name}:{release_sha}",
        "android_signing": {
            "expected_build_signer_role": expected_build_signer_role,
            "expected_build_signer_fingerprint": expected_build_signer["fingerprint"],
            "expected_build_signer_origin": expected_build_signer["apk_key_hash_origin"],
            "certificates": normalized_certificates,
        },
        "env_vars": rendered_env,
        "secret_env": normalized_secrets,
        "runtime": runtime,
    }
    if additional_origins:
        contract["webauthn_additional_origins"] = additional_origins
    # The main service runs end-to-end HTTP/2 so uploads over 32 MiB reach it, and Cloud Run does
    # not pass WebSockets to such a service. The deploy workflow therefore runs the event stream as
    # a second HTTP/1 service from the same image, and the load balancer sends the stream's path to
    # it. Terraform derives the same name. Cloud Run's longest request timeout keeps a relay
    # connected for an hour before it reconnects with its cursor.
    if env_vars.get("ATPROTO_FIREHOSE_ENABLED") == "true":
        contract["repo_event_stream"] = {
            "service_name": f"{service_name}-repo-events",
            "timeout_seconds": 3600,
        }
    pathlib.Path(output_path).write_text(json.dumps(contract, separators=(",", ":")))
except ContractError as error:
    print(f"ERROR: {error}", file=sys.stderr)
    raise SystemExit(1)
except Exception:
    print("ERROR: deployment contract validation failed.", file=sys.stderr)
    raise SystemExit(1)
PY
  exit 1
fi

if ! jq -S . "$VALIDATED_JSON" > "$SORTED_JSON" 2> "$JQ_SORT_LOG"; then
  die "failed to sort the validated deployment contract."
fi

cat "$SORTED_JSON"
