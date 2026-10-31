#!/usr/bin/env bash
# Real-behavior tests for deterministic, fail-closed Cloud Run contracts.

set -euo pipefail

OPERATOR_ROOT="$(git rev-parse --show-toplevel)"
# shellcheck source=scripts/tests/lib/assertions.sh
source "$OPERATOR_ROOT/scripts/tests/lib/assertions.sh"

SCRIPT="scripts/render-cloud-run-contract.sh"
CREDENTIAL_SENTINEL="CREDENTIAL_SENTINEL_MUST_NOT_LEAK"
TMP_DIR="$(mktemp -d)"
chmod 700 "$TMP_DIR"
SOURCE_REPO="$TMP_DIR/source-repo"
CURRENT_INPUTS_REPO="$TMP_DIR/current-inputs-repo"
FAKE_BIN="$TMP_DIR/bin"
FIXTURE_DIR="$TMP_DIR/fixtures"
LOG_DIR="$TMP_DIR/logs"
REAL_JQ="$(command -v jq)"
REAL_TERRAFORM="$(command -v terraform)"
STATUS_BEFORE="$(git -C "$OPERATOR_ROOT" status --short)"
OPERATOR_SOURCE_HASHES_BEFORE="$TMP_DIR/operator-source-hashes.before"
OPERATOR_SOURCE_HASHES_AFTER="$TMP_DIR/operator-source-hashes.after"
SOURCE_REPO_HASHES_BEFORE="$TMP_DIR/source-repo-hashes.before"
SOURCE_REPO_HASHES_AFTER="$TMP_DIR/source-repo-hashes.after"
CURRENT_REPO_HASHES_BEFORE="$TMP_DIR/current-repo-hashes.before"
CURRENT_REPO_HASHES_AFTER="$TMP_DIR/current-repo-hashes.after"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$FAKE_BIN" "$FIXTURE_DIR" "$LOG_DIR"

snapshot_operator_sources() {
  local output_file="$1" source_root="${2:-$OPERATOR_ROOT}"
  python3 - "$source_root" > "$output_file" << 'PY'
import hashlib
import os
import pathlib
import stat
import sys

source_root = pathlib.Path(sys.argv[1])
terraform_root = source_root / "infra" / "terraform"


def relative(path: pathlib.Path) -> str:
    return path.relative_to(source_root).as_posix()


def record(path: pathlib.Path) -> None:
    path_stat = path.lstat()
    mode = oct(stat.S_IMODE(path_stat.st_mode))
    if path.is_symlink():
        print(f"L {relative(path)} {mode} {os.readlink(path)}")
    elif path.is_dir():
        print(f"D {relative(path)} {mode} {path_stat.st_mtime_ns}")
        for child in sorted(path.iterdir(), key=lambda item: item.name):
            record(child)
    elif path.is_file():
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        print(f"F {relative(path)} {mode} {path_stat.st_size} {path_stat.st_mtime_ns} {digest.hexdigest()}")
    else:
        print(f"O {relative(path)} {mode} {path_stat.st_mtime_ns}")


def is_root_snapshot_file(path: pathlib.Path) -> bool:
    name = path.name
    return (
        name.endswith(".tf")
        or name.endswith(".tfvars")
        or name == ".terraform.lock.hcl"
        or name == "staging-contract-template.json"
        or name == ".terraform.tfstate.lock.info"
        or name == "terraform.tfstate"
        or name.startswith("terraform.tfstate.")
        or name.endswith(".tfstate")
        or ".tfstate." in name
        or name == "crash.log"
        or (name.startswith("crash.") and name.endswith(".log"))
    )


for anchor_name in (".terraform", "terraform.tfstate.d"):
    anchor = terraform_root / anchor_name
    if anchor.exists() or anchor.is_symlink():
        record(anchor)
    else:
        print(f"A {relative(anchor)} absent")

if terraform_root.is_dir():
    for child in sorted(terraform_root.iterdir(), key=lambda item: item.name):
        if child.name not in {".terraform", "terraform.tfstate.d"} and is_root_snapshot_file(child):
            record(child)

record(source_root / "scripts" / "render-cloud-run-contract.sh")
PY
}

assert_runtime_snapshot_detects_mutation() {
  local probe_root="$TMP_DIR/runtime-snapshot-probe"
  local before="$TMP_DIR/runtime-snapshot-probe.before"
  local after="$TMP_DIR/runtime-snapshot-probe.after"
  mkdir -p "$probe_root/infra/terraform" "$probe_root/scripts"
  printf 'variable "probe" {}\n' > "$probe_root/infra/terraform/variables.tf"
  printf '#!/usr/bin/env bash\n' > "$probe_root/scripts/render-cloud-run-contract.sh"
  snapshot_operator_sources "$before" "$probe_root"
  mkdir -p "$probe_root/infra/terraform/terraform.tfstate.d/reviewer-workspace"
  printf 'mutation\n' > "$probe_root/infra/terraform/terraform.tfstate.d/reviewer-workspace/terraform.tfstate.backup"
  snapshot_operator_sources "$after" "$probe_root"
  cmp -s "$before" "$after" && fail "runtime snapshot did not detect a nested workspace state mutation"
  pass
}

snapshot_synthetic_sources() {
  local repo="$1" output_file="$2"
  (
    cd "$repo"
    find infra scripts -type f -print0 \
      | sort -z \
      | xargs -0 shasum -a 256
  ) > "$output_file"
}

snapshot_operator_sources "$OPERATOR_SOURCE_HASHES_BEFORE"
assert_runtime_snapshot_detects_mutation

assert_equals() {
  local expected="$1" actual="$2"
  [[ "$expected" == "$actual" ]] || fail "expected '$expected', got '$actual'"
  pass
}

assert_zero_bytes() {
  local file="$1"
  assert_equals "0" "$(wc -c < "$file" | tr -d ' ')"
}

assert_exact_line() {
  local expected="$1" file="$2"
  cmp -s <(printf '%s\n' "$expected") "$file" || fail "$file did not contain the exact expected line"
  pass
}

cat > "$FIXTURE_DIR/staging-source.json" << 'EOF'
{
  "project_id": "logdate-dev",
  "region": "us-central1",
  "service_name": "logdate-server-staging",
  "cloud_run_image": "us-central1-docker.pkg.dev/logdate-dev/logdate/logdate-server:latest",
  "runtime_service_account_name": "logdate-runtime",
  "artifact_registry_repo": "logdate",
  "artifact_registry_image_name": "logdate-server",
  "domains": ["cloud-staging.logdate.app"],
  "domain": "",
  "webauthn_additional_origins": ["https://staging.logdate.app"],
  "android_signing_certificates": {
    "staging": {
      "fingerprint": "E1:6A:82:07:74:DE:F6:29:24:EB:E1:48:67:47:8C:72:9C:69:A0:CB:9D:01:8A:8C:E4:49:44:DA:00:15:E9:5A",
      "apk_key_hash_origin": "android:apk-key-hash:4WqCB3Te9ikk6-FIZ0eMcpxpoMudAYqM5ElE2gAV6Vo"
    },
    "play_app_signing": {
      "fingerprint": "F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F",
      "apk_key_hash_origin": "android:apk-key-hash:8T710OyT7bCMbPIdihKEmULCktjt7CbA5EYMPHG8bl8"
    }
  },
  "env_vars": {
    "HOST": "0.0.0.0",
    "GCS_PROJECT_ID": "logdate-dev",
    "GCS_BUCKET_NAME": "logdate-media-staging",
    "LOGDATE_ENV": "production",
    "LOGDATE_EXPECT_FIRST_PARTY": "true",
    "LOGDATE_DEPLOYMENT_KIND": "first_party",
    "LOGDATE_SERVER_DISPLAY_NAME": "LogDate Cloud (Staging)",
    "LOGDATE_PUBLIC_ORIGIN": "https://cloud-staging.logdate.app",
    "ATPROTO_PDS_SERVICE_URL": "https://cloud-staging.logdate.app",
    "ATPROTO_HANDLE_DOMAIN": "cloud-staging.logdate.app",
    "WEBAUTHN_RP_ID": "staging.logdate.app",
    "WEBAUTHN_ORIGIN": "https://app.staging.logdate.app",
    "BILLING_PROVIDER": "play",
    "SERVER_ENCRYPTION_ENABLED": "true",
    "SYNC_MEDIA_SIGNED_URLS": "true",
    "SYNC_MEDIA_SIGNED_URL_TTL_HOURS": "1",
    "TRUSTED_PROXY_HOPS": "1",
    "AUTO_MIGRATE": "false"
  },
  "secret_env": {
    "DATABASE_URL": {"secret_id": "logdate-staging-db-url", "version": "19"},
    "DATABASE_USER": {"secret_id": "logdate-db-user", "version": "7"},
    "DATABASE_PASSWORD": {"secret_id": "logdate-db-password", "version": "11"},
    "JWT_SECRET": {"secret_id": "logdate-jwt-secret", "version": "3"},
    "ATPROTO_SIGNING_KEY_KEK": {"secret_id": "logdate-atproto-signing-key-kek", "version": "2"},
    "ATPROTO_SESSION_SECRET": {"secret_id": "logdate-atproto-session-secret", "version": "4"},
    "SERVER_ENCRYPTION_KEY": {"secret_id": "logdate-server-encryption-key", "version": "5"},
    "SERVER_ENCRYPTION_KEY_ID": {"secret_id": "logdate-server-encryption-key-id", "version": "2"},
    "HEALTH_INTERNAL_TOKEN": {"secret_id": "logdate-health-internal-token", "version": "13"}
  },
  "runtime": {
    "allow_unauthenticated": true,
    "ingress": "INGRESS_TRAFFIC_ALL",
    "port": 8080,
    "scaling": {"min_instances": 0, "max_instances": 10},
    "resources": {"cpu": "1", "memory": "512Mi", "cpu_idle": true, "startup_cpu_boost": true},
    "timeout_seconds": 60,
    "request_concurrency": 16,
    "startup_probe": {"path": "/health", "port": 8080, "timeout_seconds": 5, "period_seconds": 5, "failure_threshold": 12},
    "liveness_probe": {"path": "/health", "port": 8080, "initial_delay_seconds": 15, "timeout_seconds": 5, "period_seconds": 30, "failure_threshold": 3}
  }
}
EOF

cat > "$FIXTURE_DIR/production-source.json" << 'EOF'
{
  "project_id": "logdate",
  "region": "us-central1",
  "service_name": "logdate-server",
  "cloud_run_image": "us-docker.pkg.dev/cloudrun/container/hello",
  "runtime_service_account_name": "logdate-runtime",
  "artifact_registry_repo": "logdate",
  "artifact_registry_image_name": "logdate-server",
  "domains": ["cloud.logdate.app"],
  "domain": "",
  "webauthn_additional_origins": [],
  "android_signing_certificates": {
    "upload": {
      "fingerprint": "11:98:70:B8:78:F3:AB:5F:55:C0:DF:65:C7:87:89:C0:24:59:CA:9F:F3:22:A0:89:40:AE:43:A2:9D:1D:D5:AB",
      "apk_key_hash_origin": "android:apk-key-hash:EZhwuHjzq19VwN9lx4eJwCRZyp_zIqCJQK5Dop0d1as"
    },
    "play_app_signing": {
      "fingerprint": "F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F",
      "apk_key_hash_origin": "android:apk-key-hash:8T710OyT7bCMbPIdihKEmULCktjt7CbA5EYMPHG8bl8"
    }
  },
  "env_vars": {
    "HOST": "0.0.0.0",
    "GCS_PROJECT_ID": "logdate",
    "GCS_BUCKET_NAME": "logdate-media-logdate",
    "LOGDATE_ENV": "production",
    "LOGDATE_EXPECT_FIRST_PARTY": "true",
    "LOGDATE_DEPLOYMENT_KIND": "first_party",
    "LOGDATE_SERVER_DISPLAY_NAME": "LogDate Cloud",
    "LOGDATE_PUBLIC_ORIGIN": "https://cloud.logdate.app",
    "ATPROTO_PDS_SERVICE_URL": "https://cloud.logdate.app",
    "ATPROTO_HANDLE_DOMAIN": "logdate.app",
    "WEBAUTHN_RP_ID": "logdate.app",
    "WEBAUTHN_ORIGIN": "https://cloud.logdate.app",
    "BILLING_PROVIDER": "play",
    "SERVER_ENCRYPTION_ENABLED": "true",
    "SYNC_MEDIA_SIGNED_URLS": "true",
    "SYNC_MEDIA_SIGNED_URL_TTL_HOURS": "1",
    "TRUSTED_PROXY_HOPS": "0",
    "AUTO_MIGRATE": "false"
  },
  "secret_env": {
    "DATABASE_URL": {"secret_id": "logdate-production-db-url", "version": "17"},
    "DATABASE_USER": {"secret_id": "logdate-db-user", "version": "7"},
    "DATABASE_PASSWORD": {"secret_id": "logdate-db-password", "version": "11"},
    "JWT_SECRET": {"secret_id": "logdate-jwt-secret", "version": "3"},
    "ATPROTO_SIGNING_KEY_KEK": {"secret_id": "logdate-atproto-signing-key-kek", "version": "2"},
    "ATPROTO_SESSION_SECRET": {"secret_id": "logdate-atproto-session-secret", "version": "4"},
    "SERVER_ENCRYPTION_KEY": {"secret_id": "logdate-server-encryption-key", "version": "5"},
    "SERVER_ENCRYPTION_KEY_ID": {"secret_id": "logdate-server-encryption-key-id", "version": "2"},
    "HEALTH_INTERNAL_TOKEN": {"secret_id": "logdate-health-internal-token", "version": "13"}
  },
  "runtime": {
    "allow_unauthenticated": true,
    "ingress": "INGRESS_TRAFFIC_ALL",
    "port": 8080,
    "scaling": {"min_instances": 1, "max_instances": 10},
    "resources": {"cpu": "1", "memory": "512Mi", "cpu_idle": true, "startup_cpu_boost": true},
    "timeout_seconds": 60,
    "request_concurrency": 16,
    "startup_probe": {"path": "/health", "port": 8080, "timeout_seconds": 5, "period_seconds": 5, "failure_threshold": 12},
    "liveness_probe": {"path": "/health", "port": 8080, "initial_delay_seconds": 15, "timeout_seconds": 5, "period_seconds": 30, "failure_threshold": 3}
  }
}
EOF

initialize_synthetic_repo() {
  local repo="$1"
  mkdir -p "$repo/infra/terraform" "$repo/scripts"
  cp "$OPERATOR_ROOT"/infra/terraform/*.tf "$repo/infra/terraform/"
  cp "$OPERATOR_ROOT/infra/terraform/.terraform.lock.hcl" "$repo/infra/terraform/.terraform.lock.hcl"
  cp "$OPERATOR_ROOT/infra/terraform/staging-contract-template.json" "$repo/infra/terraform/staging-contract-template.json"
  cp "$OPERATOR_ROOT/$SCRIPT" "$repo/$SCRIPT"
  chmod +x "$repo/$SCRIPT"
  git -C "$repo" init -q
  git -C "$repo" config user.name 'Task 1 Test'
  git -C "$repo" config user.email 'task-1@example.invalid'
}

commit_synthetic_repo() {
  local repo="$1" message="$2" parent="${3:-}" tree commit
  git -C "$repo" update-index --add -- \
    scripts/render-cloud-run-contract.sh \
    infra/terraform/.terraform.lock.hcl \
    infra/terraform/staging-contract-template.json \
    infra/terraform/*.tf \
    infra/terraform/staging.tfvars \
    infra/terraform/production.tfvars
  tree="$(git -C "$repo" write-tree)"
  if [[ -n "$parent" ]]; then
    commit="$(printf '%s\n' "$message" | git -C "$repo" commit-tree "$tree" -p "$parent")"
  else
    commit="$(printf '%s\n' "$message" | git -C "$repo" commit-tree "$tree")"
  fi
  git -C "$repo" update-ref HEAD "$commit"
  printf '%s\n' "$commit"
}

# Bind the current checkout sources in the existing disposable fixture before
# invoking the renderer, which intentionally requires committed input bytes.
initialize_synthetic_repo "$CURRENT_INPUTS_REPO"
cp "$OPERATOR_ROOT/infra/terraform/staging.tfvars" "$CURRENT_INPUTS_REPO/infra/terraform/staging.tfvars"
cp "$OPERATOR_ROOT/infra/terraform/production.tfvars" "$CURRENT_INPUTS_REPO/infra/terraform/production.tfvars"
CURRENT_RELEASE_SHA="$(commit_synthetic_repo "$CURRENT_INPUTS_REPO" 'test: create current-input renderer release')"
snapshot_synthetic_sources "$CURRENT_INPUTS_REPO" "$CURRENT_REPO_HASHES_BEFORE"
assert_equals "$(shasum -a 256 "$OPERATOR_ROOT/infra/terraform/staging.tfvars" | awk '{print $1}')" \
  "$(shasum -a 256 "$CURRENT_INPUTS_REPO/infra/terraform/staging.tfvars" | awk '{print $1}')"
assert_equals "$(shasum -a 256 "$OPERATOR_ROOT/infra/terraform/production.tfvars" | awk '{print $1}')" \
  "$(shasum -a 256 "$CURRENT_INPUTS_REPO/infra/terraform/production.tfvars" | awk '{print $1}')"

# CI staging uses the committed template rather than Terraform evaluation.
assert_equals "true" "$(jq -r '.env_vars.LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED' \
  "$OPERATOR_ROOT/infra/terraform/staging-contract-template.json")"

# Because CI deploys staging from that template instead of evaluating Terraform, the template has
# to describe exactly what staging.tfvars does. Otherwise a new secret or setting committed to
# staging.tfvars never reaches staging.
TEMPLATE_CONTRACT="$(cd "$CURRENT_INPUTS_REPO" && CI=true "./$SCRIPT" --environment staging \
  --release-sha "$CURRENT_RELEASE_SHA" 2> "$LOG_DIR/staging-template-render.err" | jq -S .)" \
  || fail "staging contract could not be rendered from staging-contract-template.json"
TERRAFORM_CONTRACT="$(cd "$CURRENT_INPUTS_REPO" && CI=false "./$SCRIPT" --environment staging \
  --release-sha "$CURRENT_RELEASE_SHA" 2> "$LOG_DIR/staging-terraform-render.err" | jq -S .)" \
  || fail "staging contract could not be rendered from staging.tfvars"
assert_equals "$TERRAFORM_CONTRACT" "$TEMPLATE_CONTRACT"

initialize_synthetic_repo "$SOURCE_REPO"
cat > "$SOURCE_REPO/infra/terraform/staging.tfvars" << 'EOF'
# committed-valid-staging
project_id                  = "logdate-dev"
region                      = "us-central1"
service_name                = "logdate-server-staging"
artifact_registry_repo      = "logdate"
artifact_registry_image_name = "logdate-server"
cloud_run_image             = "mutable-placeholder"
domains                     = ["cloud-staging.logdate.app"]
domain                      = ""
webauthn_rp_id              = "staging.logdate.app"
webauthn_origin             = "https://app.staging.logdate.app"
webauthn_additional_origins = ["https://staging.logdate.app"]
gcs_bucket_name             = "logdate-media-staging"
request_concurrency         = 16
android_signing_certificates = {
  staging = {
    fingerprint         = "E1:6A:82:07:74:DE:F6:29:24:EB:E1:48:67:47:8C:72:9C:69:A0:CB:9D:01:8A:8C:E4:49:44:DA:00:15:E9:5A"
    apk_key_hash_origin = "android:apk-key-hash:4WqCB3Te9ikk6-FIZ0eMcpxpoMudAYqM5ElE2gAV6Vo"
  }
  play_app_signing = {
    fingerprint         = "F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F"
    apk_key_hash_origin = "android:apk-key-hash:8T710OyT7bCMbPIdihKEmULCktjt7CbA5EYMPHG8bl8"
  }
}
cloud_run_env = {
  LOGDATE_ENV                      = "production"
  LOGDATE_EXPECT_FIRST_PARTY       = "true"
  LOGDATE_DEPLOYMENT_KIND          = "first_party"
  LOGDATE_SERVER_DISPLAY_NAME      = "LogDate Cloud (Staging)"
  LOGDATE_PUBLIC_ORIGIN            = "https://cloud-staging.logdate.app"
  ATPROTO_PDS_SERVICE_URL          = "https://cloud-staging.logdate.app"
  ATPROTO_HANDLE_DOMAIN            = "cloud-staging.logdate.app"
  BILLING_PROVIDER                 = "play"
  SERVER_ENCRYPTION_ENABLED        = "true"
  SYNC_MEDIA_SIGNED_URLS           = "true"
  SYNC_MEDIA_SIGNED_URL_TTL_HOURS  = "1"
  TRUSTED_PROXY_HOPS               = "1"
  AUTO_MIGRATE                     = "false"
}
cloud_run_secret_env = {
  DATABASE_URL             = { secret_id = "logdate-staging-db-url", version = "19" }
  DATABASE_USER            = { secret_id = "logdate-db-user", version = "7" }
  DATABASE_PASSWORD        = { secret_id = "logdate-db-password", version = "11" }
  JWT_SECRET               = { secret_id = "logdate-jwt-secret", version = "3" }
  ATPROTO_SIGNING_KEY_KEK  = { secret_id = "logdate-atproto-signing-key-kek", version = "2" }
  ATPROTO_SESSION_SECRET   = { secret_id = "logdate-atproto-session-secret", version = "4" }
  SERVER_ENCRYPTION_KEY    = { secret_id = "logdate-server-encryption-key", version = "5" }
  SERVER_ENCRYPTION_KEY_ID = { secret_id = "logdate-server-encryption-key-id", version = "2" }
  HEALTH_INTERNAL_TOKEN    = { secret_id = "logdate-health-internal-token", version = "13" }
}
EOF
cat > "$SOURCE_REPO/infra/terraform/production.tfvars" << 'EOF'
# committed-valid-production
project_id                   = "logdate"
region                       = "us-central1"
service_name                 = "logdate-server"
artifact_registry_repo       = "logdate"
artifact_registry_image_name = "logdate-server"
cloud_run_image              = "mutable-placeholder"
domains                      = ["cloud.logdate.app"]
domain                       = ""
webauthn_rp_id               = "logdate.app"
webauthn_origin              = "https://cloud.logdate.app"
gcs_bucket_name              = "logdate-media-logdate"
min_instances                = 1
request_concurrency          = 16
android_signing_certificates = {
  upload = {
    fingerprint         = "11:98:70:B8:78:F3:AB:5F:55:C0:DF:65:C7:87:89:C0:24:59:CA:9F:F3:22:A0:89:40:AE:43:A2:9D:1D:D5:AB"
    apk_key_hash_origin = "android:apk-key-hash:EZhwuHjzq19VwN9lx4eJwCRZyp_zIqCJQK5Dop0d1as"
  }
  play_app_signing = {
    fingerprint         = "F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F"
    apk_key_hash_origin = "android:apk-key-hash:8T710OyT7bCMbPIdihKEmULCktjt7CbA5EYMPHG8bl8"
  }
}
cloud_run_env = {
  LOGDATE_ENV                     = "production"
  LOGDATE_EXPECT_FIRST_PARTY      = "true"
  LOGDATE_DEPLOYMENT_KIND         = "first_party"
  LOGDATE_SERVER_DISPLAY_NAME     = "LogDate Cloud"
  LOGDATE_PUBLIC_ORIGIN           = "https://cloud.logdate.app"
  ATPROTO_PDS_SERVICE_URL         = "https://cloud.logdate.app"
  ATPROTO_HANDLE_DOMAIN           = "logdate.app"
  BILLING_PROVIDER                = "play"
  SERVER_ENCRYPTION_ENABLED       = "true"
  SYNC_MEDIA_SIGNED_URLS          = "true"
  SYNC_MEDIA_SIGNED_URL_TTL_HOURS = "1"
  TRUSTED_PROXY_HOPS              = "0"
  AUTO_MIGRATE                    = "false"
}
cloud_run_secret_env = {
  DATABASE_URL             = { secret_id = "logdate-production-db-url", version = "17" }
  DATABASE_USER            = { secret_id = "logdate-db-user", version = "7" }
  DATABASE_PASSWORD        = { secret_id = "logdate-db-password", version = "11" }
  JWT_SECRET               = { secret_id = "logdate-jwt-secret", version = "3" }
  ATPROTO_SIGNING_KEY_KEK  = { secret_id = "logdate-atproto-signing-key-kek", version = "2" }
  ATPROTO_SESSION_SECRET   = { secret_id = "logdate-atproto-session-secret", version = "4" }
  SERVER_ENCRYPTION_KEY    = { secret_id = "logdate-server-encryption-key", version = "5" }
  SERVER_ENCRYPTION_KEY_ID = { secret_id = "logdate-server-encryption-key-id", version = "2" }
  HEALTH_INTERNAL_TOKEN    = { secret_id = "logdate-health-internal-token", version = "13" }
}
EOF
RELEASE_SHA="$(commit_synthetic_repo "$SOURCE_REPO" 'test: create valid renderer release')"
NON_COMMIT_SHA="$(printf 'not a commit\n' | git -C "$SOURCE_REPO" hash-object -w --stdin)"

# Create a release commit that omits the dependency lockfile without changing the checkout.
git -C "$SOURCE_REPO" update-index --force-remove -- infra/terraform/.terraform.lock.hcl
MISSING_LOCK_TREE="$(git -C "$SOURCE_REPO" write-tree)"
MISSING_LOCK_SHA="$(printf '%s\n' 'test: create lockfile-free renderer release' \
  | git -C "$SOURCE_REPO" commit-tree "$MISSING_LOCK_TREE" -p "$RELEASE_SHA")"
git -C "$SOURCE_REPO" update-index --add -- infra/terraform/.terraform.lock.hcl

# Create a second commit whose renderer does not match the executable under test.
printf '\n# mismatched committed renderer\n' >> "$SOURCE_REPO/$SCRIPT"
MISMATCH_SHA="$(commit_synthetic_repo "$SOURCE_REPO" 'test: create mismatched renderer release' "$RELEASE_SHA")"
cp "$OPERATOR_ROOT/$SCRIPT" "$SOURCE_REPO/$SCRIPT"
chmod +x "$SOURCE_REPO/$SCRIPT"
git -C "$SOURCE_REPO" update-index --add -- "$SCRIPT"

# Hostile uncommitted and staged inputs must not influence the release-bound projection.
printf '\n# DIRTY_WORKTREE_POISON\n' >> "$SOURCE_REPO/infra/terraform/variables.tf"
git -C "$SOURCE_REPO" update-index --add -- infra/terraform/variables.tf
printf 'allow_unauthenticated = false\n' > "$SOURCE_REPO/infra/terraform/staging.tfvars"
printf 'allow_unauthenticated = false\n' > "$SOURCE_REPO/infra/terraform/production.tfvars"
printf 'allow_unauthenticated = false\n' > "$SOURCE_REPO/infra/terraform/terraform.tfvars"
printf 'allow_unauthenticated = false\n' > "$SOURCE_REPO/infra/terraform/task-1-poison.auto.tfvars"
mkdir -p "$SOURCE_REPO/infra/terraform/.terraform" "$SOURCE_REPO/infra/terraform/terraform.tfstate.d/production"
printf 'synthetic checkout plugin metadata\n' > "$SOURCE_REPO/infra/terraform/.terraform/backend-marker"
printf 'synthetic checkout state\n' > "$SOURCE_REPO/infra/terraform/terraform.tfstate"
printf 'synthetic checkout workspace\n' > "$SOURCE_REPO/infra/terraform/terraform.tfstate.d/production/state-marker"
snapshot_synthetic_sources "$SOURCE_REPO" "$SOURCE_REPO_HASHES_BEFORE"

cat > "$FAKE_BIN/terraform" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="${TEST_LOG_DIR:?}"
TF_DATA_DIR="${TF_DATA_DIR:?}"
[[ -d "$TF_DATA_DIR" ]] || exit 91
while IFS='=' read -r name _; do
    case "$name" in
        TF_DATA_DIR | TF_INPUT) ;;
        TF_*) printf 'ambient Terraform variable remained: %s\n' "$name" >"$LOG_DIR/preflight-failure.log"; exit 93 ;;
    esac
    [[ "$name" != GIT_* ]] || { printf 'ambient Git variable remained: %s\n' "$name" >"$LOG_DIR/preflight-failure.log"; exit 93; }
done < <(env)
[[ -z "${TF_WORKSPACE:-}" ]] || { printf 'TF_WORKSPACE remained\n' >"$LOG_DIR/preflight-failure.log"; exit 93; }
[[ -z "${TF_LOG:-}" && -z "${TF_LOG_PATH:-}" ]] || { printf 'Terraform logging remained\n' >"$LOG_DIR/preflight-failure.log"; exit 93; }
[[ "${TF_INPUT:-}" == "0" ]] || { printf 'TF_INPUT was not forced off\n' >"$LOG_DIR/preflight-failure.log"; exit 93; }
config_dir="${1#-chdir=}"
[[ "$config_dir" != "${SOURCE_TFVARS_DIR:?}" ]] || { printf 'checkout config directory used\n' >"$LOG_DIR/preflight-failure.log"; exit 93; }
[[ ! -e "$config_dir/terraform.tfvars" && ! -e "$config_dir/task-1-poison.auto.tfvars" ]] || { printf 'auto-loaded canary copied\n' >"$LOG_DIR/preflight-failure.log"; exit 93; }
[[ -f "$config_dir/.terraform.lock.hcl" && -f "$config_dir/${EXPECTED_ENVIRONMENT:?}.tfvars" ]] || { printf 'required isolated inputs missing\n' >"$LOG_DIR/preflight-failure.log"; exit 93; }
[[ "$(head -n 1 "$config_dir/${EXPECTED_ENVIRONMENT}.tfvars")" == "# committed-valid-${EXPECTED_ENVIRONMENT}" ]] || { printf 'selected tfvars did not come from release commit\n' >"$LOG_DIR/preflight-failure.log"; exit 93; }
! grep -Rq 'DIRTY_WORKTREE_POISON' "$config_dir" || { printf 'dirty worktree configuration was copied\n' >"$LOG_DIR/preflight-failure.log"; exit 93; }
config_files="$(find "$config_dir" -maxdepth 1 -type f -print | sed 's#.*/##' | sort | tr '\n' ' ')"
[[ "$config_files" == ".terraform.lock.hcl contract-locals.tf ${EXPECTED_ENVIRONMENT}.tfvars variables.tf " ]] || { printf 'unexpected isolated inputs: %s\n' "$config_files" >"$LOG_DIR/preflight-failure.log"; exit 93; }
mode="$(stat -f '%Lp' "$TF_DATA_DIR" 2>/dev/null || stat -c '%a' "$TF_DATA_DIR")"
[[ "$mode" == "700" ]] || exit 94
printf '%s\n' "$TF_DATA_DIR" >>"$LOG_DIR/data-dirs.log"
{
    for arg in "$@"; do
        printf '[%s]' "$arg"
    done
    printf '\n'
} >>"$LOG_DIR/terraform.log"

case "${2:-}" in
    init)
        [[ "$#" == "4" && "$3" == "-backend=false" && "$4" == "-input=false" ]]
        printf '# private lockfile rewrite\n' >"$config_dir/.terraform.lock.hcl"
        if [[ "${FAIL_TERRAFORM_INIT:-false}" == "true" ]]; then
            printf '%s\n' "${CREDENTIAL_SENTINEL:?}" >&2
            exit 31
        fi
        ;;
    console)
        [[ "$#" == "4" ]]
        [[ "$3" == -state=* && "$4" == "-var-file=${EXPECTED_ENVIRONMENT:?}.tfvars" ]]
        state_path="${3#-state=}"
        [[ ! -e "$state_path" ]] || exit 95
        [[ "$(dirname "$state_path")" == "$(dirname "$TF_DATA_DIR")" ]] || exit 96
        state_parent_mode="$(stat -f '%Lp' "$(dirname "$state_path")" 2>/dev/null || stat -c '%a' "$(dirname "$state_path")")"
        [[ "$state_parent_mode" == "700" ]] || exit 97
        printf '%s\n' "$state_path" >>"$LOG_DIR/state-paths.log"
        printf 'private-console-state\n' >"$state_path"
        expression="$(cat)"
        [[ "$expression" != *$'\n'* ]] || exit 98
        [[ "$expression" == *"jsonencode("* ]] || exit 98
        [[ "$expression" != *"google_sql_"* && "$expression" != *"google_service_account"* && "$expression" != *"local.cloud_sql_env"* ]] || exit 99
        printf '%s\n' "$expression" >>"$LOG_DIR/console-expression.log"
        fixture="${FAKE_TERRAFORM_FIXTURE:?}"
        case "${FAKE_CONSOLE_MODE:-valid}" in
            malformed) printf 'not-json\n'; exit 0 ;;
            null) printf 'null\n'; exit 0 ;;
            unknown) printf '%s\n' '"(known after apply)"'; exit 0 ;;
            truncated) printf '%s\n' '"{\"project_id\":'; exit 0 ;;
            prefix-failure) printf '%s' '"{\"project_id\":'; printf '%s\n' "${CREDENTIAL_SENTINEL:?}" >&2; exit 37 ;;
        esac
        encoded="$(python3 - "${FIXTURE_DIR:?}/$fixture" <<'PY'
import json
import pathlib
import sys

source = json.loads(pathlib.Path(sys.argv[1]).read_text())
print(json.dumps(json.dumps(source, separators=(",", ":"))))
PY
        )"
        case "${FAKE_CONSOLE_MODE:-valid}" in
            noisy) printf 'Terraform diagnostic noise\n%s\n' "$encoded" ;;
            multi) printf '%s\n%s\n' "$encoded" "$encoded" ;;
            valid) printf '%s\n' "$encoded" ;;
            *) exit 102 ;;
        esac
        ;;
    *)
        exit 98
        ;;
esac
EOF
chmod +x "$FAKE_BIN/terraform"
# The renderer prefers terraform-bin, which hashicorp/setup-terraform installs
# beside its wrapper; without this the real binary would bypass the fake.
ln -s terraform "$FAKE_BIN/terraform-bin"

cat > "$FAKE_BIN/jq" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAIL_JQ_SORT:-false}" == "true" && " $* " == *" -S "* ]]; then
    exit 41
fi
exec "${REAL_JQ:?}" "$@"
EOF
chmod +x "$FAKE_BIN/jq"

run_renderer() {
  local environment="$1" release_sha="$2" fixture="$3" stdout_file="$4" stderr_file="$5"
  (
    cd "$SOURCE_REPO"
    # The renderer reads a committed template instead of running terraform
    # when CI=true; these fixtures exercise the terraform path everywhere.
    CI=false \
      PATH="$FAKE_BIN:$PATH" \
      TEST_LOG_DIR="$LOG_DIR" \
      SOURCE_TFVARS_DIR="$SOURCE_REPO/infra/terraform" \
      FIXTURE_DIR="$FIXTURE_DIR" \
      REAL_JQ="$REAL_JQ" \
      CREDENTIAL_SENTINEL="$CREDENTIAL_SENTINEL" \
      FAKE_TERRAFORM_FIXTURE="$fixture" \
      EXPECTED_ENVIRONMENT="$environment" \
      TF_WORKSPACE="production" \
      TF_CLI_ARGS="-lock-timeout=1s" \
      TF_CLI_ARGS_init="-backend-config=production.hcl" \
      TF_CLI_ARGS_console="-var-file=wrong.tfvars" \
      TF_CLI_ARGS_plan="-destroy" \
      TF_VAR_android_signing_certificates='poison' \
      TF_VAR_allow_unauthenticated='false' \
      TF_LOG='TRACE' \
      TF_LOG_PATH="$TMP_DIR/terraform-trace.log" \
      TF_CLI_CONFIG_FILE="$TMP_DIR/hostile-terraform.rc" \
      TF_PLUGIN_CACHE_DIR="$TMP_DIR/hostile-plugin-cache" \
      TF_INPUT='1' \
      GIT_DIR="$TMP_DIR/hostile-git-dir" \
      GIT_WORK_TREE="$TMP_DIR/hostile-work-tree" \
      GIT_INDEX_FILE="$TMP_DIR/hostile-index" \
      GIT_OBJECT_DIRECTORY="$TMP_DIR/hostile-object-directory" \
      GIT_ALTERNATE_OBJECT_DIRECTORIES="$TMP_DIR/hostile-alternate-objects" \
      GIT_COMMON_DIR="$TMP_DIR/hostile-common-dir" \
      GIT_CEILING_DIRECTORIES="$SOURCE_REPO" \
      GIT_CONFIG_COUNT="1" \
      GIT_CONFIG_KEY_0="core.hooksPath" \
      GIT_CONFIG_VALUE_0="$TMP_DIR/hostile-hooks" \
      "$SCRIPT" --environment "$environment" --release-sha "$release_sha" > "$stdout_file" 2> "$stderr_file"
  )
}

run_real_renderer() {
  local repo="$1" environment="$2" release_sha="$3" stdout_file="$4" stderr_file="$5"
  (
    cd "$repo"
    CI=false \
      PATH="$(dirname "$REAL_TERRAFORM"):$(dirname "$REAL_JQ"):$PATH" \
      TF_WORKSPACE="production" \
      TF_CLI_ARGS="-lock-timeout=1s" \
      TF_CLI_ARGS_init="-backend-config=production.hcl" \
      TF_CLI_ARGS_console="-var-file=wrong.tfvars" \
      TF_VAR_android_signing_certificates='poison' \
      TF_VAR_allow_unauthenticated='false' \
      TF_LOG='TRACE' \
      TF_LOG_PATH="$TMP_DIR/real-terraform-trace.log" \
      TF_CLI_CONFIG_FILE="$TMP_DIR/hostile-real-terraform.rc" \
      TF_PLUGIN_CACHE_DIR="$TMP_DIR/hostile-real-plugin-cache" \
      TF_INPUT='1' \
      GIT_DIR="$TMP_DIR/hostile-real-git-dir" \
      GIT_WORK_TREE="$TMP_DIR/hostile-real-work-tree" \
      GIT_INDEX_FILE="$TMP_DIR/hostile-real-index" \
      GIT_OBJECT_DIRECTORY="$TMP_DIR/hostile-real-object-directory" \
      GIT_ALTERNATE_OBJECT_DIRECTORIES="$TMP_DIR/hostile-real-alternate-objects" \
      GIT_COMMON_DIR="$TMP_DIR/hostile-real-common-dir" \
      GIT_CEILING_DIRECTORIES="$repo" \
      GIT_CONFIG_COUNT="1" \
      GIT_CONFIG_KEY_0="core.hooksPath" \
      GIT_CONFIG_VALUE_0="$TMP_DIR/hostile-real-hooks" \
      "$SCRIPT" --environment "$environment" --release-sha "$release_sha" > "$stdout_file" 2> "$stderr_file"
  )
}

cat > "$FIXTURE_DIR/staging-contract.json" << EOF
{
  "android_package_name": "studio.hypertext.logdate",
  "android_signing": {
    "certificates": {
      "play_app_signing": {
        "apk_key_hash_origin": "android:apk-key-hash:8T710OyT7bCMbPIdihKEmULCktjt7CbA5EYMPHG8bl8",
        "fingerprint": "F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F"
      },
      "staging": {
        "apk_key_hash_origin": "android:apk-key-hash:4WqCB3Te9ikk6-FIZ0eMcpxpoMudAYqM5ElE2gAV6Vo",
        "fingerprint": "E1:6A:82:07:74:DE:F6:29:24:EB:E1:48:67:47:8C:72:9C:69:A0:CB:9D:01:8A:8C:E4:49:44:DA:00:15:E9:5A"
      }
    },
    "expected_build_signer_fingerprint": "E1:6A:82:07:74:DE:F6:29:24:EB:E1:48:67:47:8C:72:9C:69:A0:CB:9D:01:8A:8C:E4:49:44:DA:00:15:E9:5A",
    "expected_build_signer_origin": "android:apk-key-hash:4WqCB3Te9ikk6-FIZ0eMcpxpoMudAYqM5ElE2gAV6Vo",
    "expected_build_signer_role": "staging"
  },
  "canonical_origin": "https://cloud-staging.logdate.app",
  "webauthn_additional_origins": ["https://staging.logdate.app"],
  "env_vars": {
    "ANDROID_CERT_FINGERPRINTS": "E1:6A:82:07:74:DE:F6:29:24:EB:E1:48:67:47:8C:72:9C:69:A0:CB:9D:01:8A:8C:E4:49:44:DA:00:15:E9:5A,F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F",
    "ATPROTO_HANDLE_DOMAIN": "cloud-staging.logdate.app",
    "ATPROTO_PDS_SERVICE_URL": "https://cloud-staging.logdate.app",
    "AUTO_MIGRATE": "false",
    "BILLING_PROVIDER": "play",
    "GCS_BUCKET_NAME": "logdate-media-staging",
    "GCS_PROJECT_ID": "logdate-dev",
    "HOST": "0.0.0.0",
    "LOGDATE_DEPLOYMENT_KIND": "first_party",
    "LOGDATE_ENV": "production",
    "LOGDATE_EXPECT_FIRST_PARTY": "true",
    "LOGDATE_PUBLIC_ORIGIN": "https://cloud-staging.logdate.app",
    "LOGDATE_SERVER_DISPLAY_NAME": "LogDate Cloud (Staging)",
    "RELEASE_VERSION": "logdate-server@$RELEASE_SHA",
    "SERVER_ENCRYPTION_ENABLED": "true",
    "SYNC_MEDIA_SIGNED_URLS": "true",
    "SYNC_MEDIA_SIGNED_URL_TTL_HOURS": "1",
    "TRUSTED_PROXY_HOPS": "1",
    "WEBAUTHN_ALLOWED_ORIGINS": "https://app.staging.logdate.app,android:apk-key-hash:4WqCB3Te9ikk6-FIZ0eMcpxpoMudAYqM5ElE2gAV6Vo,android:apk-key-hash:8T710OyT7bCMbPIdihKEmULCktjt7CbA5EYMPHG8bl8,https://staging.logdate.app",
    "WEBAUTHN_ORIGIN": "https://app.staging.logdate.app",
    "WEBAUTHN_RP_ID": "staging.logdate.app"
  },
  "environment": "staging",
  "image": "us-central1-docker.pkg.dev/logdate-dev/logdate/logdate-server:$RELEASE_SHA",
  "project_id": "logdate-dev",
  "region": "us-central1",
  "release_sha": "$RELEASE_SHA",
  "runtime": {
    "allow_unauthenticated": true,
    "ingress": "INGRESS_TRAFFIC_ALL",
    "port": 8080,
    "liveness_probe": {"failure_threshold": 3, "initial_delay_seconds": 15, "path": "/health", "period_seconds": 30, "port": 8080, "timeout_seconds": 5},
    "request_concurrency": 16,
    "resources": {"cpu": "1", "cpu_idle": true, "memory": "512Mi", "startup_cpu_boost": true},
    "scaling": {"max_instances": 10, "min_instances": 0},
    "startup_probe": {"failure_threshold": 12, "path": "/health", "period_seconds": 5, "port": 8080, "timeout_seconds": 5},
    "timeout_seconds": 60
  },
  "runtime_service_account": "logdate-runtime@logdate-dev.iam.gserviceaccount.com",
  "secret_env": {
    "DATABASE_URL": {"secret_id": "logdate-staging-db-url", "version": "19"},
    "DATABASE_PASSWORD": {"secret_id": "logdate-db-password", "version": "11"},
    "DATABASE_USER": {"secret_id": "logdate-db-user", "version": "7"},
    "HEALTH_INTERNAL_TOKEN": {"secret_id": "logdate-health-internal-token", "version": "13"},
    "JWT_SECRET": {"secret_id": "logdate-jwt-secret", "version": "3"},
    "ATPROTO_SIGNING_KEY_KEK": {"secret_id": "logdate-atproto-signing-key-kek", "version": "2"},
    "ATPROTO_SESSION_SECRET": {"secret_id": "logdate-atproto-session-secret", "version": "4"},
    "SERVER_ENCRYPTION_KEY": {"secret_id": "logdate-server-encryption-key", "version": "5"},
    "SERVER_ENCRYPTION_KEY_ID": {"secret_id": "logdate-server-encryption-key-id", "version": "2"}
  },
  "service_name": "logdate-server-staging"
}
EOF
jq -S . "$FIXTURE_DIR/staging-contract.json" > "$FIXTURE_DIR/staging-contract.sorted.json"

STAGING_OUT="$TMP_DIR/staging.out"
STAGING_ERR="$TMP_DIR/staging.err"
set +e
run_renderer staging "$RELEASE_SHA" staging-source.json "$STAGING_OUT" "$STAGING_ERR"
staging_status=$?
set -e
if [[ "$staging_status" != "0" ]]; then
  printf 'Renderer stderr: %s\n' "$(cat "$STAGING_ERR")" >&2
  if [[ -f "$LOG_DIR/terraform.log" ]]; then
    printf 'Fake Terraform log:\n%s\n' "$(cat "$LOG_DIR/terraform.log")" >&2
  fi
  if [[ -f "$LOG_DIR/preflight-failure.log" ]]; then
    printf 'Fake Terraform preflight: %s\n' "$(cat "$LOG_DIR/preflight-failure.log")" >&2
  fi
fi
assert_exit_code 0 "$staging_status"
cmp -s "$FIXTURE_DIR/staging-contract.sorted.json" "$STAGING_OUT" || fail "staging contract did not match the hand-checked canonical fixture"
pass
assert_zero_bytes "$STAGING_ERR"

DETERMINISTIC_OUT="$TMP_DIR/staging-second.out"
DETERMINISTIC_ERR="$TMP_DIR/staging-second.err"
run_renderer staging "$RELEASE_SHA" staging-source.json "$DETERMINISTIC_OUT" "$DETERMINISTIC_ERR"
assert_zero_bytes "$DETERMINISTIC_ERR"
cmp -s "$STAGING_OUT" "$DETERMINISTIC_OUT" || fail "renderer output was not deterministic"
pass
jq -S . "$STAGING_OUT" > "$TMP_DIR/staging-resorted.json"
cmp -s "$STAGING_OUT" "$TMP_DIR/staging-resorted.json" || fail "renderer output was not sorted JSON"
pass

PRODUCTION_OUT="$TMP_DIR/production.out"
PRODUCTION_ERR="$TMP_DIR/production.err"
run_renderer production "$RELEASE_SHA" production-source.json "$PRODUCTION_OUT" "$PRODUCTION_ERR"
assert_zero_bytes "$PRODUCTION_ERR"
cat > "$FIXTURE_DIR/production-contract.json" << EOF
{
  "android_package_name": "studio.hypertext.logdate",
  "android_signing": {
    "certificates": {
      "play_app_signing": {
        "apk_key_hash_origin": "android:apk-key-hash:8T710OyT7bCMbPIdihKEmULCktjt7CbA5EYMPHG8bl8",
        "fingerprint": "F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F"
      },
      "upload": {
        "apk_key_hash_origin": "android:apk-key-hash:EZhwuHjzq19VwN9lx4eJwCRZyp_zIqCJQK5Dop0d1as",
        "fingerprint": "11:98:70:B8:78:F3:AB:5F:55:C0:DF:65:C7:87:89:C0:24:59:CA:9F:F3:22:A0:89:40:AE:43:A2:9D:1D:D5:AB"
      }
    },
    "expected_build_signer_fingerprint": "11:98:70:B8:78:F3:AB:5F:55:C0:DF:65:C7:87:89:C0:24:59:CA:9F:F3:22:A0:89:40:AE:43:A2:9D:1D:D5:AB",
    "expected_build_signer_origin": "android:apk-key-hash:EZhwuHjzq19VwN9lx4eJwCRZyp_zIqCJQK5Dop0d1as",
    "expected_build_signer_role": "upload"
  },
  "canonical_origin": "https://cloud.logdate.app",
  "env_vars": {
    "ANDROID_CERT_FINGERPRINTS": "11:98:70:B8:78:F3:AB:5F:55:C0:DF:65:C7:87:89:C0:24:59:CA:9F:F3:22:A0:89:40:AE:43:A2:9D:1D:D5:AB,F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F",
    "ATPROTO_HANDLE_DOMAIN": "logdate.app",
    "ATPROTO_PDS_SERVICE_URL": "https://cloud.logdate.app",
    "AUTO_MIGRATE": "false",
    "BILLING_PROVIDER": "play",
    "GCS_BUCKET_NAME": "logdate-media-logdate",
    "GCS_PROJECT_ID": "logdate",
    "HOST": "0.0.0.0",
    "LOGDATE_DEPLOYMENT_KIND": "first_party",
    "LOGDATE_ENV": "production",
    "LOGDATE_EXPECT_FIRST_PARTY": "true",
    "LOGDATE_PUBLIC_ORIGIN": "https://cloud.logdate.app",
    "LOGDATE_SERVER_DISPLAY_NAME": "LogDate Cloud",
    "RELEASE_VERSION": "logdate-server@$RELEASE_SHA",
    "SERVER_ENCRYPTION_ENABLED": "true",
    "SYNC_MEDIA_SIGNED_URLS": "true",
    "SYNC_MEDIA_SIGNED_URL_TTL_HOURS": "1",
    "TRUSTED_PROXY_HOPS": "0",
    "WEBAUTHN_ALLOWED_ORIGINS": "https://cloud.logdate.app,android:apk-key-hash:EZhwuHjzq19VwN9lx4eJwCRZyp_zIqCJQK5Dop0d1as,android:apk-key-hash:8T710OyT7bCMbPIdihKEmULCktjt7CbA5EYMPHG8bl8",
    "WEBAUTHN_ORIGIN": "https://cloud.logdate.app",
    "WEBAUTHN_RP_ID": "logdate.app"
  },
  "environment": "production",
  "image": "us-central1-docker.pkg.dev/logdate/logdate/logdate-server:$RELEASE_SHA",
  "project_id": "logdate",
  "region": "us-central1",
  "release_sha": "$RELEASE_SHA",
  "runtime": {
    "allow_unauthenticated": true,
    "ingress": "INGRESS_TRAFFIC_ALL",
    "port": 8080,
    "liveness_probe": {"failure_threshold": 3, "initial_delay_seconds": 15, "path": "/health", "period_seconds": 30, "port": 8080, "timeout_seconds": 5},
    "request_concurrency": 16,
    "resources": {"cpu": "1", "cpu_idle": true, "memory": "512Mi", "startup_cpu_boost": true},
    "scaling": {"max_instances": 10, "min_instances": 1},
    "startup_probe": {"failure_threshold": 12, "path": "/health", "period_seconds": 5, "port": 8080, "timeout_seconds": 5},
    "timeout_seconds": 60
  },
  "runtime_service_account": "logdate-runtime@logdate.iam.gserviceaccount.com",
  "secret_env": {
    "DATABASE_PASSWORD": {"secret_id": "logdate-db-password", "version": "11"},
    "DATABASE_URL": {"secret_id": "logdate-production-db-url", "version": "17"},
    "DATABASE_USER": {"secret_id": "logdate-db-user", "version": "7"},
    "HEALTH_INTERNAL_TOKEN": {"secret_id": "logdate-health-internal-token", "version": "13"},
    "JWT_SECRET": {"secret_id": "logdate-jwt-secret", "version": "3"},
    "ATPROTO_SIGNING_KEY_KEK": {"secret_id": "logdate-atproto-signing-key-kek", "version": "2"},
    "ATPROTO_SESSION_SECRET": {"secret_id": "logdate-atproto-session-secret", "version": "4"},
    "SERVER_ENCRYPTION_KEY": {"secret_id": "logdate-server-encryption-key", "version": "5"},
    "SERVER_ENCRYPTION_KEY_ID": {"secret_id": "logdate-server-encryption-key-id", "version": "2"}
  },
  "service_name": "logdate-server"
}
EOF
jq -S . "$FIXTURE_DIR/production-contract.json" > "$FIXTURE_DIR/production-contract.sorted.json"
cmp -s "$FIXTURE_DIR/production-contract.sorted.json" "$PRODUCTION_OUT" || fail "production contract did not match the hand-checked canonical fixture"
pass
assert_equals "production" "$(jq -r '.environment' "$PRODUCTION_OUT")"
assert_equals "https://cloud.logdate.app" "$(jq -r '.canonical_origin' "$PRODUCTION_OUT")"
assert_equals "us-central1-docker.pkg.dev/logdate/logdate/logdate-server:$RELEASE_SHA" "$(jq -r '.image' "$PRODUCTION_OUT")"
assert_equals "17" "$(jq -r '.secret_env.DATABASE_URL.version' "$PRODUCTION_OUT")"
assert_equals "2" "$(jq -r '.env_vars.ANDROID_CERT_FINGERPRINTS | split(",") | length' "$PRODUCTION_OUT")"
assert_equals "10" "$(jq -r '.runtime.scaling.max_instances' "$PRODUCTION_OUT")"
assert_equals "staging" "$(jq -r '.android_signing.expected_build_signer_role' "$STAGING_OUT")"
assert_equals "upload" "$(jq -r '.android_signing.expected_build_signer_role' "$PRODUCTION_OUT")"
assert_equals "play_app_signing,staging" "$(jq -r '.android_signing.certificates | keys | join(",")' "$STAGING_OUT")"
assert_equals "play_app_signing,upload" "$(jq -r '.android_signing.certificates | keys | join(",")' "$PRODUCTION_OUT")"
assert_equals "true" "$(jq -r '.android_signing as $signing | $signing.certificates[$signing.expected_build_signer_role].fingerprint == $signing.expected_build_signer_fingerprint and $signing.certificates[$signing.expected_build_signer_role].apk_key_hash_origin == $signing.expected_build_signer_origin' "$STAGING_OUT")"
assert_equals "true" "$(jq -r '.android_signing as $signing | $signing.certificates[$signing.expected_build_signer_role].fingerprint == $signing.expected_build_signer_fingerprint and $signing.certificates[$signing.expected_build_signer_role].apk_key_hash_origin == $signing.expected_build_signer_origin' "$PRODUCTION_OUT")"
assert_equals "false" "$(jq -r '.android_signing.expected_build_signer_fingerprint == .android_signing.certificates.play_app_signing.fingerprint' "$STAGING_OUT")"
assert_equals "false" "$(jq -r '.android_signing.expected_build_signer_fingerprint == .android_signing.certificates.play_app_signing.fingerprint' "$PRODUCTION_OUT")"
assert_equals "1" "$(jq -s 'length' "$PRODUCTION_OUT")"
assert_equals "object" "$(jq -r 'type' "$PRODUCTION_OUT")"
assert_equals "false" "$(jq -r '.env_vars | has("INSTANCE_CONNECTION_NAME") or has("DB_NAME")' "$PRODUCTION_OUT")"
assert_not_contains ':latest' "$(cat "$STAGING_OUT" "$PRODUCTION_OUT")"
assert_not_contains 'us-docker.pkg.dev/cloudrun/container/hello' "$(cat "$STAGING_OUT" "$PRODUCTION_OUT")"

REAL_STAGING_OUT="$TMP_DIR/real-staging.out"
REAL_STAGING_ERR="$TMP_DIR/real-staging.err"
run_real_renderer "$SOURCE_REPO" staging "$RELEASE_SHA" "$REAL_STAGING_OUT" "$REAL_STAGING_ERR"
cmp -s "$FIXTURE_DIR/staging-contract.sorted.json" "$REAL_STAGING_OUT" || fail "real Terraform staging contract did not match the canonical fixture"
pass
assert_zero_bytes "$REAL_STAGING_ERR"

REAL_STAGING_REPEAT_OUT="$TMP_DIR/real-staging-repeat.out"
REAL_STAGING_REPEAT_ERR="$TMP_DIR/real-staging-repeat.err"
run_real_renderer "$SOURCE_REPO" staging "$RELEASE_SHA" "$REAL_STAGING_REPEAT_OUT" "$REAL_STAGING_REPEAT_ERR"
cmp -s "$REAL_STAGING_OUT" "$REAL_STAGING_REPEAT_OUT" || fail "real Terraform staging output was not deterministic"
pass
assert_zero_bytes "$REAL_STAGING_REPEAT_ERR"

REAL_PRODUCTION_OUT="$TMP_DIR/real-production.out"
REAL_PRODUCTION_ERR="$TMP_DIR/real-production.err"
run_real_renderer "$SOURCE_REPO" production "$RELEASE_SHA" "$REAL_PRODUCTION_OUT" "$REAL_PRODUCTION_ERR"
cmp -s "$FIXTURE_DIR/production-contract.sorted.json" "$REAL_PRODUCTION_OUT" || fail "real Terraform production contract did not match the canonical fixture"
pass
assert_zero_bytes "$REAL_PRODUCTION_ERR"

CURRENT_STAGING_OUT="$TMP_DIR/current-staging.out"
CURRENT_STAGING_ERR="$TMP_DIR/current-staging.err"
run_real_renderer "$CURRENT_INPUTS_REPO" staging "$CURRENT_RELEASE_SHA" "$CURRENT_STAGING_OUT" "$CURRENT_STAGING_ERR" \
  || fail "current committed staging inputs did not render"
pass
assert_zero_bytes "$CURRENT_STAGING_ERR"
assert_equals "first_party" "$(jq -r '.env_vars.LOGDATE_DEPLOYMENT_KIND' "$CURRENT_STAGING_OUT")"

CURRENT_PRODUCTION_OUT="$TMP_DIR/current-production.out"
CURRENT_PRODUCTION_ERR="$TMP_DIR/current-production.err"
run_real_renderer "$CURRENT_INPUTS_REPO" production "$CURRENT_RELEASE_SHA" "$CURRENT_PRODUCTION_OUT" "$CURRENT_PRODUCTION_ERR" \
  || fail "current committed production inputs did not render"
pass
assert_zero_bytes "$CURRENT_PRODUCTION_ERR"
assert_equals "first_party" "$(jq -r '.env_vars.LOGDATE_DEPLOYMENT_KIND' "$CURRENT_PRODUCTION_OUT")"
# The upload certificate's apk-key-hash origin has to reach the runtime
# allowlist. Without it the server rejects the ceremony an install signed with
# that certificate presents, which is the failure this whole contract exists to
# prevent.
assert_contains 'android:apk-key-hash:' "$(jq -r '.env_vars.WEBAUTHN_ALLOWED_ORIGINS' "$CURRENT_PRODUCTION_OUT")"

# CI's provider-free variables must accept the same committed operator settings
# while leaving the runtime contract identical to a normal Terraform render.
CURRENT_PRODUCTION_CI_OUT="$TMP_DIR/current-production-ci.out"
CURRENT_PRODUCTION_CI_ERR="$TMP_DIR/current-production-ci.err"
(
  cd "$CURRENT_INPUTS_REPO"
  CI=true PATH="$(dirname "$REAL_TERRAFORM"):$(dirname "$REAL_JQ"):$PATH" \
    "$SCRIPT" --environment production --release-sha "$CURRENT_RELEASE_SHA" \
    > "$CURRENT_PRODUCTION_CI_OUT" 2> "$CURRENT_PRODUCTION_CI_ERR"
) || fail "current committed production inputs did not render in CI"
cmp -s "$CURRENT_PRODUCTION_OUT" "$CURRENT_PRODUCTION_CI_OUT" \
  || fail "CI production contract differed from the normal Terraform render"
pass
assert_zero_bytes "$CURRENT_PRODUCTION_CI_ERR"

expect_failure() {
  local label="$1" environment="$2" release_sha="$3" fixture="$4" expected_error="$5"
  local output_file="$TMP_DIR/$label.out" error_file="$TMP_DIR/$label.err" status
  set +e
  run_renderer "$environment" "$release_sha" "$fixture" "$output_file" "$error_file"
  status=$?
  set -e
  [[ "$status" != "0" ]] || fail "$label unexpectedly succeeded"
  pass
  assert_zero_bytes "$output_file"
  if ! grep -Fq -- "$expected_error" "$error_file"; then
    fail "$label expected stderr to contain '$expected_error'; got: $(cat "$error_file")"
  fi
  pass
}

jq 'del(.android_signing_certificates.play_app_signing)' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/staging-only-signing.json"
STAGING_ONLY_OUT="$TMP_DIR/staging-only.out"
STAGING_ONLY_ERR="$TMP_DIR/staging-only.err"
set +e
run_renderer staging "$RELEASE_SHA" staging-only-signing.json "$STAGING_ONLY_OUT" "$STAGING_ONLY_ERR"
staging_only_status=$?
set -e
assert_exit_code 0 "$staging_only_status"
assert_zero_bytes "$STAGING_ONLY_ERR"
assert_equals "staging" "$(jq -r '.android_signing.certificates | keys | join(",")' "$STAGING_ONLY_OUT")"
assert_equals \
  "$(jq -r '([.env_vars.WEBAUTHN_ORIGIN, .android_signing.certificates.staging.apk_key_hash_origin] + (.webauthn_additional_origins // [])) | join(",")' "$STAGING_ONLY_OUT")" \
  "$(jq -r '.env_vars.WEBAUTHN_ALLOWED_ORIGINS' "$STAGING_ONLY_OUT")"

# Production before the application exists in Play Console. Play issues the
# app-signing certificate at creation time, so requiring it here would make the
# first production deploy impossible. The upload certificate alone is a
# complete, valid signer set; sideloaded release builds are signed with it.
jq 'del(.android_signing_certificates.play_app_signing)' \
  "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/production-only-signing.json"
PRODUCTION_ONLY_OUT="$TMP_DIR/production-only.out"
PRODUCTION_ONLY_ERR="$TMP_DIR/production-only.err"
set +e
run_renderer production "$RELEASE_SHA" production-only-signing.json "$PRODUCTION_ONLY_OUT" "$PRODUCTION_ONLY_ERR"
production_only_status=$?
set -e
assert_exit_code 0 "$production_only_status"
assert_zero_bytes "$PRODUCTION_ONLY_ERR"
assert_equals "upload" "$(jq -r '.android_signing.certificates | keys | join(",")' "$PRODUCTION_ONLY_OUT")"
assert_equals \
  "$(jq -r '[.env_vars.WEBAUTHN_ORIGIN, .android_signing.certificates.upload.apk_key_hash_origin] | join(",")' "$PRODUCTION_ONLY_OUT")" \
  "$(jq -r '.env_vars.WEBAUTHN_ALLOWED_ORIGINS' "$PRODUCTION_ONLY_OUT")"

jq '(.android_signing_certificates[].fingerprint) |= ascii_downcase' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/lowercase-staging-signing.json"
LOWERCASE_STAGING_OUT="$TMP_DIR/lowercase-staging-signing.out"
LOWERCASE_STAGING_ERR="$TMP_DIR/lowercase-staging-signing.err"
run_renderer staging "$RELEASE_SHA" lowercase-staging-signing.json "$LOWERCASE_STAGING_OUT" "$LOWERCASE_STAGING_ERR"
assert_zero_bytes "$LOWERCASE_STAGING_ERR"
assert_equals "E1:6A:82:07:74:DE:F6:29:24:EB:E1:48:67:47:8C:72:9C:69:A0:CB:9D:01:8A:8C:E4:49:44:DA:00:15:E9:5A" \
  "$(jq -r '.android_signing.certificates.staging.fingerprint' "$LOWERCASE_STAGING_OUT")"
assert_equals "F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F" \
  "$(jq -r '.android_signing.certificates.play_app_signing.fingerprint' "$LOWERCASE_STAGING_OUT")"
assert_equals \
  "$(jq -r '[.android_signing.certificates.staging.fingerprint, .android_signing.certificates.play_app_signing.fingerprint] | join(",")' "$LOWERCASE_STAGING_OUT")" \
  "$(jq -r '.env_vars.ANDROID_CERT_FINGERPRINTS' "$LOWERCASE_STAGING_OUT")"
assert_equals \
  "$(jq -r '([.env_vars.WEBAUTHN_ORIGIN, .android_signing.certificates.staging.apk_key_hash_origin, .android_signing.certificates.play_app_signing.apk_key_hash_origin] + (.webauthn_additional_origins // [])) | join(",")' "$LOWERCASE_STAGING_OUT")" \
  "$(jq -r '.env_vars.WEBAUTHN_ALLOWED_ORIGINS' "$LOWERCASE_STAGING_OUT")"
assert_equals \
  "$(jq -r '.android_signing.certificates[.android_signing.expected_build_signer_role].fingerprint' "$LOWERCASE_STAGING_OUT")" \
  "$(jq -r '.android_signing.expected_build_signer_fingerprint' "$LOWERCASE_STAGING_OUT")"

jq '(.android_signing_certificates[].fingerprint) |= ascii_downcase' \
  "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/lowercase-production-signing.json"
LOWERCASE_PRODUCTION_OUT="$TMP_DIR/lowercase-production-signing.out"
LOWERCASE_PRODUCTION_ERR="$TMP_DIR/lowercase-production-signing.err"
run_renderer production "$RELEASE_SHA" lowercase-production-signing.json "$LOWERCASE_PRODUCTION_OUT" "$LOWERCASE_PRODUCTION_ERR"
assert_zero_bytes "$LOWERCASE_PRODUCTION_ERR"
assert_equals "11:98:70:B8:78:F3:AB:5F:55:C0:DF:65:C7:87:89:C0:24:59:CA:9F:F3:22:A0:89:40:AE:43:A2:9D:1D:D5:AB" \
  "$(jq -r '.android_signing.certificates.upload.fingerprint' "$LOWERCASE_PRODUCTION_OUT")"
assert_equals "F1:3E:F5:D0:EC:93:ED:B0:8C:6C:F2:1D:8A:12:84:99:42:C2:92:D8:ED:EC:26:C0:E4:46:0C:3C:71:BC:6E:5F" \
  "$(jq -r '.android_signing.certificates.play_app_signing.fingerprint' "$LOWERCASE_PRODUCTION_OUT")"
assert_equals \
  "$(jq -r '[.android_signing.certificates.upload.fingerprint, .android_signing.certificates.play_app_signing.fingerprint] | join(",")' "$LOWERCASE_PRODUCTION_OUT")" \
  "$(jq -r '.env_vars.ANDROID_CERT_FINGERPRINTS' "$LOWERCASE_PRODUCTION_OUT")"
assert_equals \
  "$(jq -r '[.env_vars.WEBAUTHN_ORIGIN, .android_signing.certificates.upload.apk_key_hash_origin, .android_signing.certificates.play_app_signing.apk_key_hash_origin] | join(",")' "$LOWERCASE_PRODUCTION_OUT")" \
  "$(jq -r '.env_vars.WEBAUTHN_ALLOWED_ORIGINS' "$LOWERCASE_PRODUCTION_OUT")"
assert_equals \
  "$(jq -r '.android_signing.certificates[.android_signing.expected_build_signer_role].fingerprint' "$LOWERCASE_PRODUCTION_OUT")" \
  "$(jq -r '.android_signing.expected_build_signer_fingerprint' "$LOWERCASE_PRODUCTION_OUT")"

expect_failure invalid-environment preview "$RELEASE_SHA" staging-source.json "environment must be staging or production"
expect_failure invalid-sha staging ABCDEF staging-source.json "release SHA must be 40 lowercase hexadecimal characters"
expect_failure nonexistent-release staging 0000000000000000000000000000000000000001 staging-source.json "release SHA must resolve to a commit"
expect_failure non-commit-release staging "$NON_COMMIT_SHA" staging-source.json "release SHA must resolve to a commit"
expect_failure mismatched-renderer staging "$MISMATCH_SHA" staging-source.json "executed renderer does not match the release commit"
expect_failure missing-lockfile staging "$MISSING_LOCK_SHA" staging-source.json "release commit does not contain the Terraform dependency lockfile"

FAIL_TERRAFORM_INIT="true" expect_failure \
  terraform-init-failure staging "$RELEASE_SHA" staging-source.json "Terraform backend-free initialization failed"

for console_mode in noisy multi malformed null unknown truncated; do
  FAKE_CONSOLE_MODE="$console_mode" expect_failure \
    "console-$console_mode" staging "$RELEASE_SHA" staging-source.json "Terraform console did not return one concrete JSON object"
done

FAKE_CONSOLE_MODE="prefix-failure" expect_failure \
  console-prefix-failure staging "$RELEASE_SHA" staging-source.json "Terraform console failed"

FAIL_JQ_SORT="true" expect_failure jq-sort-failure staging "$RELEASE_SHA" staging-source.json "failed to sort the validated deployment contract"

jq '.env_vars.LOGDATE_PUBLIC_ORIGIN = "http://cloud-staging.logdate.app"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/http-origin.json"
expect_failure http-origin staging "$RELEASE_SHA" http-origin.json "LOGDATE_PUBLIC_ORIGIN must be the canonical HTTPS origin"

jq '.android_signing_certificates.staging.apk_key_hash_origin = "android:apk-key-hash:ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/certificate-mismatch.json"
expect_failure certificate-mismatch staging "$RELEASE_SHA" certificate-mismatch.json "Android certificate fingerprints and apk-key-hash origins must match exactly"

jq '.android_signing_certificates.play_app_signing.apk_key_hash_origin = .android_signing_certificates.staging.apk_key_hash_origin' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/staging-play-certificate-mismatch.json"
expect_failure staging-play-certificate-mismatch staging "$RELEASE_SHA" staging-play-certificate-mismatch.json "Android certificate fingerprints and apk-key-hash origins must match exactly"

jq '.android_signing_certificates.play_app_signing.apk_key_hash_origin = .android_signing_certificates.upload.apk_key_hash_origin' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/production-play-certificate-mismatch.json"
expect_failure production-play-certificate-mismatch production "$RELEASE_SHA" production-play-certificate-mismatch.json "Android certificate fingerprints and apk-key-hash origins must match exactly"

jq '.artifact_registry_image_name = "logdate-server-staging"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/wrong-image-name.json"
expect_failure wrong-image-name staging "$RELEASE_SHA" wrong-image-name.json "artifact_registry_image_name must be logdate-server"

# Child relying parties are valid for staging; production's RP and browser origin are not.
jq '.env_vars.WEBAUTHN_RP_ID = "logdate.app"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/wrong-staging-rp.json"
expect_failure wrong-staging-rp staging "$RELEASE_SHA" wrong-staging-rp.json \
  "staging relying party must not be the production relying party or its ancestor"
for production_host in app.logdate.app cloud.logdate.app; do
  jq --arg host "$production_host" \
    '.env_vars.WEBAUTHN_RP_ID = $host | .env_vars.WEBAUTHN_ORIGIN = ("https://" + $host)' \
    "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/production-staging-auth-origin.json"
  expect_failure production-staging-auth-origin staging "$RELEASE_SHA" production-staging-auth-origin.json \
    "staging must not authorize production WebAuthn browser origins"
done

# Additional browser origins are explicit, exact and isolated to staging.
origin_case=0
for origins in \
  '["https://staging.logdate.app/"]' \
  '["https://staging.logdate.app:443"]' \
  '["https://user@app.cloud-staging.logdate.app"]' \
  '["https://staging.logdate.app?query=1"]' \
  '["https://staging.logdate.app#fragment"]' \
  '["https://*.cloud-staging.logdate.app"]' \
  '["https://unapproved.example.test"]' \
  '["https://staging.logdate.app","https://staging.logdate.app"]'; do
  origin_case=$((origin_case + 1))
  jq --argjson origins "$origins" '.webauthn_additional_origins = $origins' \
    "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/bad-browser-origin-$origin_case.json"
  expect_failure "bad-browser-origin-$origin_case" staging "$RELEASE_SHA" \
    "bad-browser-origin-$origin_case.json" \
    "WebAuthn browser origins must be unique origin-only HTTPS URLs within the relying party"
done
jq '.webauthn_additional_origins = ["https://staging.logdate.app"]' \
  "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/production-browser-origin.json"
expect_failure production-browser-origin production "$RELEASE_SHA" production-browser-origin.json \
  "production does not declare additional WebAuthn browser origins"

jq '.domains += ["extra.logdate.app"]' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/extra-domain.json"
expect_failure extra-domain staging "$RELEASE_SHA" extra-domain.json "environment domains must contain exactly cloud-staging.logdate.app"

jq '.domains = ["other.logdate.app"]' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/wrong-domain.json"
expect_failure wrong-domain staging "$RELEASE_SHA" wrong-domain.json "environment domains must contain exactly cloud-staging.logdate.app"

jq '.domain = "cloud-staging.logdate.app"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/equal-legacy-domain.json"
expect_failure equal-legacy-domain staging "$RELEASE_SHA" equal-legacy-domain.json "legacy domain shim must be blank"

jq '.domain = "conflict.logdate.app"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/conflicting-legacy-domain.json"
expect_failure conflicting-legacy-domain staging "$RELEASE_SHA" conflicting-legacy-domain.json "legacy domain shim must be blank"

jq '.android_signing_certificates.staging = {"fingerprint":"DF:32:69:D4:DC:C9:C4:FE:72:FE:61:62:A0:F4:E9:EE:5F:04:14:47:DC:B3:8E:F6:A9:25:76:FC:38:90:DB:C7","apk_key_hash_origin":"android:apk-key-hash:3zJp1NzJxP5y_mFioPTp7l8EFEfcs472qSV2_DiQ28c"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/debug-staging-set.json"
expect_failure debug-staging-set staging "$RELEASE_SHA" debug-staging-set.json "staging certificate set may not contain the known debug certificate"

jq '.android_signing_certificates.play_app_signing = {"fingerprint":"DF:32:69:D4:DC:C9:C4:FE:72:FE:61:62:A0:F4:E9:EE:5F:04:14:47:DC:B3:8E:F6:A9:25:76:FC:38:90:DB:C7","apk_key_hash_origin":"android:apk-key-hash:3zJp1NzJxP5y_mFioPTp7l8EFEfcs472qSV2_DiQ28c"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/debug-staging-play-set.json"
expect_failure debug-staging-play-set staging "$RELEASE_SHA" debug-staging-play-set.json "staging certificate set may not contain the known debug certificate"

jq '.android_signing_certificates.play_app_signing = {"fingerprint":"DF:32:69:D4:DC:C9:C4:FE:72:FE:61:62:A0:F4:E9:EE:5F:04:14:47:DC:B3:8E:F6:A9:25:76:FC:38:90:DB:C7","apk_key_hash_origin":"android:apk-key-hash:3zJp1NzJxP5y_mFioPTp7l8EFEfcs472qSV2_DiQ28c"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/debug-production-play-set.json"
expect_failure debug-production-play-set production "$RELEASE_SHA" debug-production-play-set.json "production certificate sets may not contain the known debug certificate"

jq '.android_signing_certificates.staging = {"fingerprint":"00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00","apk_key_hash_origin":"android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/zero-staging-set.json"
expect_failure zero-staging-set staging "$RELEASE_SHA" zero-staging-set.json "staging certificate set may not contain placeholder certificates"

jq '.android_signing_certificates.play_app_signing = {"fingerprint":"00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00","apk_key_hash_origin":"android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/zero-staging-play-set.json"
expect_failure zero-staging-play-set staging "$RELEASE_SHA" zero-staging-play-set.json "staging certificate set may not contain placeholder certificates"

jq '.android_signing_certificates.play_app_signing = {"fingerprint":"00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00","apk_key_hash_origin":"android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/zero-production-play-set.json"
expect_failure zero-production-play-set production "$RELEASE_SHA" zero-production-play-set.json "production certificate sets may not contain placeholder certificates"

jq '.android_signing_certificates.staging = {"fingerprint":"FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF","apk_key_hash_origin":"android:apk-key-hash:__________________________________________8"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/repeated-staging-set.json"
expect_failure repeated-staging-set staging "$RELEASE_SHA" repeated-staging-set.json "staging certificate set may not contain placeholder certificates"

jq '.android_signing_certificates.staging = {"fingerprint":"00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F","apk_key_hash_origin":"android:apk-key-hash:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/sequential-staging-set.json"
expect_failure sequential-staging-set staging "$RELEASE_SHA" sequential-staging-set.json "staging certificate set may not contain placeholder certificates"

jq '.env_vars.ANDROID_CERT_FINGERPRINTS = "00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/reserved-aggregate.json"
expect_failure reserved-aggregate staging "$RELEASE_SHA" reserved-aggregate.json "cloud_run_env must not set reserved Android aggregate keys"

jq '.secret_env.JWT_SECRET.version = "latest"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/latest-version.json"
expect_failure latest-version staging "$RELEASE_SHA" latest-version.json "JWT_SECRET secret version must be an exact positive integer"

numeric_version_cases=(
  'del(.secret_env.JWT_SECRET.version)'
  '.secret_env.JWT_SECRET.version = "0"'
  '.secret_env.JWT_SECRET.version = "01"'
  '.secret_env.JWT_SECRET.version = "-1"'
  '.secret_env.JWT_SECRET.version = "+1"'
  '.secret_env.JWT_SECRET.version = "1.0"'
  '.secret_env.JWT_SECRET.version = " 1"'
  '.secret_env.JWT_SECRET.version = true'
  '.secret_env.JWT_SECRET.version = 1'
)
numeric_version_labels=(omitted zero leading-zero negative signed decimal whitespace boolean numeric)
for index in "${!numeric_version_cases[@]}"; do
  label="${numeric_version_labels[$index]}-version"
  jq "${numeric_version_cases[$index]}" "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/$label.json"
  expect_failure "$label" staging "$RELEASE_SHA" "$label.json" "JWT_SECRET secret version must be an exact positive integer"
done

jq '.secret_env.SENTRY_DSN = {"secret_id":"logdate-sentry-dsn","version":"latest"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/optional-latest-version.json"
expect_failure optional-latest-version staging "$RELEASE_SHA" optional-latest-version.json "SENTRY_DSN secret version must be an exact positive integer"

jq --arg sentinel "$CREDENTIAL_SENTINEL" '.secret_env.JWT_SECRET = {"secret_id":$sentinel,"version":"latest"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/credential-sentinel.json"
expect_failure credential-sentinel staging "$RELEASE_SHA" credential-sentinel.json "JWT_SECRET secret version must be an exact positive integer"

jq '.env_vars.INSTANCE_CONNECTION_NAME = "logdate-dev:us-central1:logdate-db"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/cloud-sql-connection.json"
expect_failure cloud-sql-connection staging "$RELEASE_SHA" cloud-sql-connection.json "environment contract contains unexpected keys"

jq '.env_vars.DB_NAME = "logdate"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/cloud-sql-database.json"
expect_failure cloud-sql-database staging "$RELEASE_SHA" cloud-sql-database.json "environment contract contains unexpected keys"

jq '.secret_env.DATABASE_URL.secret_id = " "' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/blank-database-url.json"
expect_failure blank-database-url production "$RELEASE_SHA" blank-database-url.json "DATABASE_URL secret ID must be a non-empty single-line string"

jq '.runtime.scaling.max_instances = 0' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-runtime.json"
expect_failure invalid-runtime staging "$RELEASE_SHA" invalid-runtime.json "runtime scaling must have max_instances greater than or equal to min_instances and at least 1"

jq '.runtime.port = 0' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-runtime-port.json"
expect_failure invalid-runtime-port staging "$RELEASE_SHA" invalid-runtime-port.json "runtime port must be 8080"

jq '.runtime.allow_unauthenticated = false' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/private-runtime.json"
expect_failure private-runtime staging "$RELEASE_SHA" private-runtime.json "first-party runtime must allow unauthenticated canonical traffic"

jq '.runtime.ingress = "INGRESS_TRAFFIC_INTERNAL_ONLY"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/internal-ingress.json"
expect_failure internal-ingress staging "$RELEASE_SHA" internal-ingress.json "first-party runtime ingress must be INGRESS_TRAFFIC_ALL"

jq 'del(.runtime.startup_probe.failure_threshold)' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/incomplete-runtime.json"
expect_failure incomplete-runtime staging "$RELEASE_SHA" incomplete-runtime.json "runtime.startup_probe must contain exactly"

jq '.runtime.startup_probe.port = 0' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-probe-port.json"
expect_failure invalid-probe-port staging "$RELEASE_SHA" invalid-probe-port.json "runtime probe ports must be 8080"

jq '.runtime.liveness_probe.path = "/ready"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-probe-path.json"
expect_failure invalid-probe-path staging "$RELEASE_SHA" invalid-probe-path.json "runtime probe paths must be /health"

jq '.runtime.resources.cpu = ""' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-resource.json"
expect_failure invalid-resource staging "$RELEASE_SHA" invalid-resource.json "runtime CPU and memory must be non-empty strings"

jq '.runtime.resources.cpu = "garbage"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-cpu.json"
expect_failure invalid-cpu staging "$RELEASE_SHA" invalid-cpu.json "runtime CPU must be a positive numeric value"

jq '.runtime.resources.memory = "0Mi"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-memory.json"
expect_failure invalid-memory staging "$RELEASE_SHA" invalid-memory.json "runtime memory must be a positive Mi or Gi value"

jq '.runtime.request_concurrency = 0' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-concurrency.json"
expect_failure invalid-concurrency staging "$RELEASE_SHA" invalid-concurrency.json "runtime request_concurrency must be a positive integer"

jq '.runtime.timeout_seconds = 0' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-timeout.json"
expect_failure invalid-timeout staging "$RELEASE_SHA" invalid-timeout.json "runtime timeout_seconds must be a positive integer"

jq '.runtime.scaling.min_instances = -1' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/negative-scaling.json"
expect_failure negative-scaling staging "$RELEASE_SHA" negative-scaling.json "runtime scaling must use non-negative integers"

jq '.unexpected = true' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/unexpected-top-level.json"
expect_failure unexpected-top-level staging "$RELEASE_SHA" unexpected-top-level.json "Terraform source projection contains unexpected keys"

# Encrypted unlock is an explicit opt-in; a deployed contract must preserve the boolean verbatim.
for enabled in true false; do
  jq --arg enabled "$enabled" '.env_vars.LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED = $enabled' \
    "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/encrypted-unlock-$enabled.json"
  output="$TMP_DIR/encrypted-unlock-$enabled.out.json"
  if ! run_renderer staging "$RELEASE_SHA" "encrypted-unlock-$enabled.json" "$output" "$LOG_DIR/encrypted-unlock-$enabled.err"; then
    cat "$LOG_DIR/encrypted-unlock-$enabled.err" >&2
    fail "encrypted unlock opt-in could not be rendered"
  fi
  assert_equals "$enabled" "$(jq -r '.env_vars.LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED' "$output")"
done
jq '.env_vars.LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED = "yes"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-encrypted-unlock.json"
expect_failure invalid-encrypted-unlock staging "$RELEASE_SHA" invalid-encrypted-unlock.json \
  "LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED must be true or false"

# Personal spaces stay off until a deployment opts in; the flag is a boolean like encrypted unlock.
for enabled in true false; do
  jq --arg enabled "$enabled" '.env_vars.LOGDATE_PERSONAL_SPACE_ENABLED = $enabled' \
    "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/personal-space-$enabled.json"
  output="$TMP_DIR/personal-space-$enabled.out.json"
  if ! run_renderer staging "$RELEASE_SHA" "personal-space-$enabled.json" "$output" "$LOG_DIR/personal-space-$enabled.err"; then
    cat "$LOG_DIR/personal-space-$enabled.err" >&2
    fail "personal space opt-in could not be rendered"
  fi
  assert_equals "$enabled" "$(jq -r '.env_vars.LOGDATE_PERSONAL_SPACE_ENABLED' "$output")"
done
jq '.env_vars.LOGDATE_PERSONAL_SPACE_ENABLED = "1"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-personal-space.json"
expect_failure invalid-personal-space staging "$RELEASE_SHA" invalid-personal-space.json \
  "LOGDATE_PERSONAL_SPACE_ENABLED must be true or false"
# Recovery keys are an opt-in in every environment, a boolean like the other server flags.
for environment in staging production; do
  for enabled in true false; do
    jq --arg enabled "$enabled" '.env_vars.ATPROTO_RECOVERY_KEYS_ENABLED = $enabled' \
      "$FIXTURE_DIR/$environment-source.json" > "$FIXTURE_DIR/recovery-keys-$environment-$enabled.json"
    output="$TMP_DIR/recovery-keys-$environment-$enabled.out.json"
    if ! run_renderer "$environment" "$RELEASE_SHA" "recovery-keys-$environment-$enabled.json" "$output" "$LOG_DIR/recovery-keys-$environment-$enabled.err"; then
      cat "$LOG_DIR/recovery-keys-$environment-$enabled.err" >&2
      fail "recovery keys opt-in could not be rendered for $environment"
    fi
    assert_equals "$enabled" "$(jq -r '.env_vars.ATPROTO_RECOVERY_KEYS_ENABLED' "$output")"
  done
done
jq '.env_vars.ATPROTO_RECOVERY_KEYS_ENABLED = "yes"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-recovery-keys.json"
expect_failure invalid-recovery-keys staging "$RELEASE_SHA" invalid-recovery-keys.json \
  "ATPROTO_RECOVERY_KEYS_ENABLED must be true or false"
# Passkey sign-in for other AT Protocol apps is an opt-in for every environment, preserved verbatim.
for environment in staging production; do
  for enabled in true false; do
    jq --arg enabled "$enabled" '.env_vars.LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED = $enabled' \
      "$FIXTURE_DIR/$environment-source.json" > "$FIXTURE_DIR/passkey-oauth-$environment-$enabled.json"
    output="$TMP_DIR/passkey-oauth-$environment-$enabled.out.json"
    if ! run_renderer "$environment" "$RELEASE_SHA" "passkey-oauth-$environment-$enabled.json" "$output" "$LOG_DIR/passkey-oauth-$environment-$enabled.err"; then
      cat "$LOG_DIR/passkey-oauth-$environment-$enabled.err" >&2
      fail "passkey sign-in opt-in could not be rendered for $environment"
    fi
    assert_equals "$enabled" "$(jq -r '.env_vars.LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED' "$output")"
  done
done
jq '.env_vars.LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED = "yes"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-passkey-oauth.json"
expect_failure invalid-passkey-oauth staging "$RELEASE_SHA" invalid-passkey-oauth.json \
  "LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED must be true or false"
# The public repository event stream is an opt-in for every environment, preserved verbatim.
for environment in staging production; do
  for enabled in true false; do
    jq --arg enabled "$enabled" '.env_vars.ATPROTO_FIREHOSE_ENABLED = $enabled' \
      "$FIXTURE_DIR/$environment-source.json" > "$FIXTURE_DIR/firehose-$environment-$enabled.json"
    output="$TMP_DIR/firehose-$environment-$enabled.out.json"
    if ! run_renderer "$environment" "$RELEASE_SHA" "firehose-$environment-$enabled.json" "$output" "$LOG_DIR/firehose-$environment-$enabled.err"; then
      cat "$LOG_DIR/firehose-$environment-$enabled.err" >&2
      fail "event stream opt-in could not be rendered for $environment"
    fi
    assert_equals "$enabled" "$(jq -r '.env_vars.ATPROTO_FIREHOSE_ENABLED' "$output")"
    # With the stream on, the deploy also runs it as its own HTTP/1 service, because the main
    # service's end-to-end HTTP/2 keeps Cloud Run from passing WebSockets to it.
    if [[ "$enabled" == "true" ]]; then
      assert_equals "$(jq -r '.service_name' "$output")-repo-events" \
        "$(jq -r '.repo_event_stream.service_name' "$output")"
      assert_equals "3600" "$(jq -r '.repo_event_stream.timeout_seconds' "$output")"
    else
      assert_equals "false" "$(jq -r 'has("repo_event_stream")' "$output")"
    fi
  done
done
jq '.env_vars.ATPROTO_FIREHOSE_ENABLED = "yes"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-firehose.json"
expect_failure invalid-firehose staging "$RELEASE_SHA" invalid-firehose.json \
  "ATPROTO_FIREHOSE_ENABLED must be true or false"
# Relays are named only in production, only with the stream on and identities published, and only
# as plain hostnames.
jq '.env_vars.ATPROTO_FIREHOSE_ENABLED = "true" | .env_vars.ATPROTO_PLC_PUBLISH_ENABLED = "true" | .env_vars.ATPROTO_RELAY_HOSTS = "relay1.example.com,relay2.example.net"' \
  "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/relay-hosts.json"
output="$TMP_DIR/relay-hosts.out.json"
if ! run_renderer production "$RELEASE_SHA" "relay-hosts.json" "$output" "$LOG_DIR/relay-hosts.err"; then
  cat "$LOG_DIR/relay-hosts.err" >&2
  fail "relay hosts could not be rendered for production"
fi
assert_equals "relay1.example.com,relay2.example.net" "$(jq -r '.env_vars.ATPROTO_RELAY_HOSTS' "$output")"
jq '.env_vars.ATPROTO_FIREHOSE_ENABLED = "true" | .env_vars.ATPROTO_RELAY_HOSTS = "relay.example.com"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/staging-relay-hosts.json"
expect_failure staging-relay-hosts staging "$RELEASE_SHA" staging-relay-hosts.json \
  "only production may ask relays to crawl the server"
jq '.env_vars.ATPROTO_FIREHOSE_ENABLED = "false" | .env_vars.ATPROTO_RELAY_HOSTS = "relay.example.com"' \
  "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/relay-hosts-stream-off.json"
expect_failure relay-hosts-stream-off production "$RELEASE_SHA" relay-hosts-stream-off.json \
  "ATPROTO_RELAY_HOSTS needs ATPROTO_FIREHOSE_ENABLED to be true"
for publishing in absent false; do
  jq --arg publishing "$publishing" '
        .env_vars.ATPROTO_FIREHOSE_ENABLED = "true" |
        .env_vars.ATPROTO_RELAY_HOSTS = "relay.example.com" |
        if $publishing == "absent" then del(.env_vars.ATPROTO_PLC_PUBLISH_ENABLED)
        else .env_vars.ATPROTO_PLC_PUBLISH_ENABLED = $publishing end' \
    "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/relay-hosts-unpublished-$publishing.json"
  expect_failure "relay-hosts-unpublished-$publishing" production "$RELEASE_SHA" "relay-hosts-unpublished-$publishing.json" \
    "ATPROTO_RELAY_HOSTS needs ATPROTO_PLC_PUBLISH_ENABLED to be true"
done
for invalid in "" "https://relay.example.com" "relay.example.com/path" "relay.example.com, relay2.example.com" "Relay.Example.com" "relay" "relay.example.com,relay.example.com" "relay.example.com:443"; do
  jq --arg hosts "$invalid" '.env_vars.ATPROTO_FIREHOSE_ENABLED = "true" | .env_vars.ATPROTO_PLC_PUBLISH_ENABLED = "true" | .env_vars.ATPROTO_RELAY_HOSTS = $hosts' \
    "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/invalid-relay-hosts.json"
  expect_failure invalid-relay-hosts production "$RELEASE_SHA" invalid-relay-hosts.json \
    "ATPROTO_RELAY_HOSTS must be a comma-separated list of distinct lowercase hostnames"
done
# Moving existing journals into personal spaces is an opt-in for every environment, preserved verbatim.
for environment in staging production; do
  for enabled in true false; do
    jq --arg enabled "$enabled" '.env_vars.LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED = $enabled' \
      "$FIXTURE_DIR/$environment-source.json" > "$FIXTURE_DIR/journal-moves-$environment-$enabled.json"
    output="$TMP_DIR/journal-moves-$environment-$enabled.out.json"
    if ! run_renderer "$environment" "$RELEASE_SHA" "journal-moves-$environment-$enabled.json" "$output" "$LOG_DIR/journal-moves-$environment-$enabled.err"; then
      cat "$LOG_DIR/journal-moves-$environment-$enabled.err" >&2
      fail "journal moves opt-in could not be rendered for $environment"
    fi
    assert_equals "$enabled" "$(jq -r '.env_vars.LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED' "$output")"
  done
done
jq '.env_vars.LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED = "yes"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-journal-moves.json"
expect_failure invalid-journal-moves staging "$RELEASE_SHA" invalid-journal-moves.json \
  "LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED must be true or false"
# Pausing the deletion of moved journals' old data is a switch for every environment, preserved verbatim.
for environment in staging production; do
  for enabled in true false; do
    jq --arg enabled "$enabled" '.env_vars.LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED = $enabled' \
      "$FIXTURE_DIR/$environment-source.json" > "$FIXTURE_DIR/old-data-purge-$environment-$enabled.json"
    output="$TMP_DIR/old-data-purge-$environment-$enabled.out.json"
    if ! run_renderer "$environment" "$RELEASE_SHA" "old-data-purge-$environment-$enabled.json" "$output" "$LOG_DIR/old-data-purge-$environment-$enabled.err"; then
      cat "$LOG_DIR/old-data-purge-$environment-$enabled.err" >&2
      fail "old data purge switch could not be rendered for $environment"
    fi
    assert_equals "$enabled" "$(jq -r '.env_vars.LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED' "$output")"
  done
done
jq '.env_vars.LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED = "no"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/invalid-old-data-purge.json"
expect_failure invalid-old-data-purge staging "$RELEASE_SHA" invalid-old-data-purge.json \
  "LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED must be true or false"
# Identity publishing is a production-only opt-in, preserved verbatim.
for enabled in true false; do
  jq --arg enabled "$enabled" '.env_vars.ATPROTO_PLC_PUBLISH_ENABLED = $enabled' \
    "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/plc-publishing-$enabled.json"
  output="$TMP_DIR/plc-publishing-$enabled.out.json"
  if ! run_renderer production "$RELEASE_SHA" "plc-publishing-$enabled.json" "$output" "$LOG_DIR/plc-publishing-$enabled.err"; then
    cat "$LOG_DIR/plc-publishing-$enabled.err" >&2
    fail "identity publishing opt-in could not be rendered"
  fi
  assert_equals "$enabled" "$(jq -r '.env_vars.ATPROTO_PLC_PUBLISH_ENABLED' "$output")"
done
jq '.env_vars.ATPROTO_PLC_PUBLISH_ENABLED = "yes"' \
  "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/invalid-plc-publishing.json"
expect_failure invalid-plc-publishing production "$RELEASE_SHA" invalid-plc-publishing.json \
  "ATPROTO_PLC_PUBLISH_ENABLED must be true or false"
jq '.env_vars.ATPROTO_PLC_PUBLISH_ENABLED = "true"' \
  "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/staging-plc-publishing.json"
expect_failure staging-plc-publishing staging "$RELEASE_SHA" staging-plc-publishing.json \
  "only production may publish identities to the PLC directory"

jq '.env_vars.UNEXPECTED_ENV = "value"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/unexpected-env.json"
expect_failure unexpected-env staging "$RELEASE_SHA" unexpected-env.json "environment contract contains unexpected keys"

jq '.secret_env.UNEXPECTED_SECRET = {"secret_id":"unexpected","version":"1"}' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/unexpected-secret.json"
expect_failure unexpected-secret staging "$RELEASE_SHA" unexpected-secret.json "secret contract contains unexpected keys"

jq '.runtime.unexpected = true' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/unexpected-runtime.json"
expect_failure unexpected-runtime staging "$RELEASE_SHA" unexpected-runtime.json "runtime must contain exactly"

jq '.env_vars.AUTO_MIGRATE = "true"' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/auto-migrate.json"
expect_failure auto-migrate staging "$RELEASE_SHA" auto-migrate.json "AUTO_MIGRATE must be false"

jq 'del(.android_signing_certificates.upload)' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/missing-upload-set.json"
expect_failure missing-upload-set production "$RELEASE_SHA" missing-upload-set.json "production requires separately identified Android upload certificate fingerprints and origins"

jq '.android_signing_certificates.play_app_signing = .android_signing_certificates.upload' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/duplicate-production-set.json"
expect_failure duplicate-production-set production "$RELEASE_SHA" duplicate-production-set.json "production upload and Play app-signing certificates must be distinct"

jq '.android_signing_certificates.play_app_signing = .android_signing_certificates.staging' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/duplicate-staging-set.json"
expect_failure duplicate-staging-set staging "$RELEASE_SHA" duplicate-staging-set.json "staging and Play app-signing certificates must be distinct"

jq '.android_signing_certificates.upload = {"fingerprint":"DF:32:69:D4:DC:C9:C4:FE:72:FE:61:62:A0:F4:E9:EE:5F:04:14:47:DC:B3:8E:F6:A9:25:76:FC:38:90:DB:C7","apk_key_hash_origin":"android:apk-key-hash:3zJp1NzJxP5y_mFioPTp7l8EFEfcs472qSV2_DiQ28c"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/debug-production-set.json"
expect_failure debug-production-set production "$RELEASE_SHA" debug-production-set.json "production certificate sets may not contain the known debug certificate"

jq '.android_signing_certificates.upload = {"fingerprint":"00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00","apk_key_hash_origin":"android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/placeholder-production-set.json"
expect_failure placeholder-production-set production "$RELEASE_SHA" placeholder-production-set.json "production certificate sets may not contain placeholder certificates"

jq '.android_signing_certificates.upload = {"fingerprint":"00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F","apk_key_hash_origin":"android:apk-key-hash:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/sequential-production-set.json"
expect_failure sequential-production-set production "$RELEASE_SHA" sequential-production-set.json "production certificate sets may not contain placeholder certificates"

jq '.android_signing_certificates.upload = {"fingerprint":"01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F:20","apk_key_hash_origin":"android:apk-key-hash:AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/offset-sequential-production-set.json"
expect_failure offset-sequential-production-set production "$RELEASE_SHA" offset-sequential-production-set.json "production certificate sets may not contain placeholder certificates"

jq '.android_signing_certificates.upload = {"fingerprint":"E0:E1:E2:E3:E4:E5:E6:E7:E8:E9:EA:EB:EC:ED:EE:EF:F0:F1:F2:F3:F4:F5:F6:F7:F8:F9:FA:FB:FC:FD:FE:FF","apk_key_hash_origin":"android:apk-key-hash:4OHi4-Tl5ufo6err7O3u7_Dx8vP09fb3-Pn6-_z9_v8"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/high-sequential-production-set.json"
expect_failure high-sequential-production-set production "$RELEASE_SHA" high-sequential-production-set.json "production certificate sets may not contain placeholder certificates"

jq '.android_signing_certificates.upload = {"fingerprint":"20:1F:1E:1D:1C:1B:1A:19:18:17:16:15:14:13:12:11:10:0F:0E:0D:0C:0B:0A:09:08:07:06:05:04:03:02:01","apk_key_hash_origin":"android:apk-key-hash:IB8eHRwbGhkYFxYVFBMSERAPDg0MCwoJCAcGBQQDAgE"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/descending-production-set.json"
expect_failure descending-production-set production "$RELEASE_SHA" descending-production-set.json "production certificate sets may not contain placeholder certificates"

jq '.android_signing_certificates.upload = {"fingerprint":"F0:F1:F2:F3:F4:F5:F6:F7:F8:F9:FA:FB:FC:FD:FE:FF:00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F","apk_key_hash_origin":"android:apk-key-hash:8PHy8_T19vf4-fr7_P3-_wABAgMEBQYHCAkKCwwNDg8"}' "$FIXTURE_DIR/production-source.json" > "$FIXTURE_DIR/wrapping-sequential-production-set.json"
expect_failure wrapping-sequential-production-set production "$RELEASE_SHA" wrapping-sequential-production-set.json "production certificate sets may not contain placeholder certificates"

jq '.android_signing_certificates.upload = .android_signing_certificates.staging' "$FIXTURE_DIR/staging-source.json" > "$FIXTURE_DIR/unexpected-staging-role.json"
expect_failure unexpected-staging-role staging "$RELEASE_SHA" unexpected-staging-role.json "staging requires a staging signer and may include the Play app-signing certificate"

while IFS= read -r data_dir; do
  [[ ! -e "$data_dir" ]] || fail "expected renderer temporary directory to be removed: $data_dir"
done < <(sort -u "$LOG_DIR/data-dirs.log")
pass
while IFS= read -r state_path; do
  [[ ! -e "$state_path" ]] || fail "expected renderer private state to be removed: $state_path"
done < <(sort -u "$LOG_DIR/state-paths.log")
pass
assert_file_not_contains 'backend-config' "$LOG_DIR/terraform.log"
assert_file_not_contains 'wrong.tfvars' "$LOG_DIR/terraform.log"
assert_file_contains 'jsonencode(' "$LOG_DIR/console-expression.log"
assert_file_contains 'var.android_signing_certificates' "$LOG_DIR/console-expression.log"
assert_file_contains 'if certificate != null' "$LOG_DIR/console-expression.log"
assert_file_not_contains 'google_sql_' "$LOG_DIR/console-expression.log"
assert_file_not_contains 'google_service_account' "$LOG_DIR/console-expression.log"
assert_file_not_contains 'local.cloud_sql_env' "$LOG_DIR/console-expression.log"
console_count="$(grep -c '\[init\]' "$LOG_DIR/terraform.log")"
unique_data_dir_count="$(sort -u "$LOG_DIR/data-dirs.log" | wc -l | tr -d ' ')"
assert_equals "$console_count" "$unique_data_dir_count"
all_error_output="$(find "$TMP_DIR" -maxdepth 1 -name '*.err' -type f -exec cat {} +)"
assert_not_contains "$CREDENTIAL_SENTINEL" "$all_error_output"

snapshot_synthetic_sources "$SOURCE_REPO" "$SOURCE_REPO_HASHES_AFTER"
cmp -s "$SOURCE_REPO_HASHES_BEFORE" "$SOURCE_REPO_HASHES_AFTER" || fail "renderer changed the valid synthetic source repository"
pass
snapshot_synthetic_sources "$CURRENT_INPUTS_REPO" "$CURRENT_REPO_HASHES_AFTER"
cmp -s "$CURRENT_REPO_HASHES_BEFORE" "$CURRENT_REPO_HASHES_AFTER" || fail "renderer changed the current-input synthetic repository"
pass

snapshot_operator_sources "$OPERATOR_SOURCE_HASHES_AFTER"
cmp -s "$OPERATOR_SOURCE_HASHES_BEFORE" "$OPERATOR_SOURCE_HASHES_AFTER" || fail "renderer tests changed operator Terraform sources"
pass
assert_equals "$STATUS_BEFORE" "$(git -C "$OPERATOR_ROOT" status --short)"

print_pass_summary "Cloud Run contract renderer"
