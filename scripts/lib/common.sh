#!/usr/bin/env bash
# Shared helpers for repository scripts. Source it; don't execute it.
#
#   source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"
#
# With NON_INTERACTIVE=true, prompts fail instead of blocking.

[[ -n "${LOGDATE_COMMON_SH_LOADED:-}" ]] && return 0
LOGDATE_COMMON_SH_LOADED=1

LOGDATE_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NON_INTERACTIVE="${NON_INTERACTIVE:-false}"

if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  C_GREEN='\033[0;32m' C_YELLOW='\033[1;33m' C_RED='\033[0;31m'
  C_CYAN='\033[0;36m' C_BOLD='\033[1m' C_RESET='\033[0m'
else
  C_GREEN='' C_YELLOW='' C_RED='' C_CYAN='' C_BOLD='' C_RESET=''
fi

log_phase() { printf '\n%b==> %s%b\n' "${C_CYAN}${C_BOLD}" "$1" "$C_RESET"; }
log_info() { printf '%b[info]%b %s\n' "$C_GREEN" "$C_RESET" "$1"; }
log_warn() { printf '%b[warn]%b %s\n' "$C_YELLOW" "$C_RESET" "$1"; }
log_error() { printf '%b[error]%b %s\n' "$C_RED" "$C_RESET" "$1" >&2; }

die() {
  log_error "$1"
  exit 1
}

require_cmd() {
  local cmd
  for cmd in "$@"; do
    command -v "$cmd" > /dev/null 2>&1 || die "'$cmd' is required but not installed."
  done
}

prompt_value() {
  local prompt="$1"
  local value=""
  [[ "$NON_INTERACTIVE" == "true" ]] && die "$prompt is required and --non-interactive was set."
  read -rp "$prompt: " value
  printf '%s' "$value"
}

prompt_secret() {
  local prompt="$1"
  local value=""
  [[ "$NON_INTERACTIVE" == "true" ]] && die "$prompt is required and --non-interactive was set."
  read -rsp "$prompt: " value
  echo >&2
  printf '%s' "$value"
}

# Defaults to "no"; always "no" when non-interactive.
confirm() {
  local prompt="$1"
  local reply=""
  [[ "$NON_INTERACTIVE" == "true" ]] && return 1
  read -rp "$prompt [y/N]: " reply
  reply="$(printf '%s' "$reply" | tr '[:upper:]' '[:lower:]')"
  [[ "$reply" == "y" || "$reply" == "yes" ]]
}

# owner/repo of the origin remote, e.g. TheHypertextStudio/logdate-mobile.
repo_slug() {
  local url
  url="$(git -C "$LOGDATE_REPO_ROOT" remote get-url origin 2> /dev/null)" || return 1
  url="${url%.git}"
  url="${url#*github.com[:/]}"
  printf '%s' "$url"
}

# environment_value ENV KEY — a value from scripts/setup/environments/<ENV>.conf;
# fails when the key is unset.
environment_value() {
  (
    # shellcheck source=/dev/null
    source "$LOGDATE_REPO_ROOT/scripts/setup/environments/$1.conf" || exit 1
    printf '%s' "${!2:?$2 is not set in scripts/setup/environments/$1.conf}"
  )
}

sha256_file() {
  shasum -a 256 "$1" | cut -d' ' -f1
}

# The checkout that owns .git; differs from LOGDATE_REPO_ROOT in a worktree.
primary_checkout() {
  local common_dir
  common_dir="$(git -C "$LOGDATE_REPO_ROOT" rev-parse --path-format=absolute --git-common-dir)" || return 1
  dirname "$common_dir"
}
