#!/usr/bin/env bash
# Shared assertions for repository shell tests.

set -euo pipefail

PASS_COUNT=0

repo_root() {
  git rev-parse --show-toplevel
}

enter_repo_root() {
  cd "$(repo_root)"
}

fail() {
  echo "FAIL: $1"
  exit 1
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
}

assert_exit_code() {
  local expected="$1"
  local actual="$2"
  if [[ "$expected" != "$actual" ]]; then
    fail "expected exit $expected, got $actual"
  fi
  pass
}

assert_contains() {
  local needle="$1"
  local text="$2"
  if ! grep -Fq -- "$needle" <<< "$text"; then
    fail "expected text to contain '$needle'"
  fi
  pass
}

assert_not_contains() {
  local needle="$1"
  local text="$2"
  if grep -Fq -- "$needle" <<< "$text"; then
    fail "expected text to not contain '$needle'"
  fi
  pass
}

assert_file_exists() {
  local file="$1"
  [[ -f "$file" ]] || fail "expected $file to exist"
  pass
}

assert_file_missing() {
  local file="$1"
  [[ ! -f "$file" ]] || fail "expected $file to be removed"
  pass
}

assert_file_contains() {
  local needle="$1"
  local file="$2"
  grep -Fq -- "$needle" "$file" || fail "expected $file to contain: $needle"
  pass
}

# Like assert_file_contains, but ignores runs of spaces, so a Markdown table that a formatter
# re-padded still matches the same cells.
assert_file_contains_ignoring_padding() {
  local needle="$1"
  local file="$2"
  local squeezed_needle squeezed_file
  squeezed_needle="$(printf '%s' "$needle" | tr -s ' ')"
  squeezed_file="$(tr -s ' ' < "$file")"
  grep -Fq -- "$squeezed_needle" <<< "$squeezed_file" || fail "expected $file to contain: $needle"
  pass
}

assert_file_not_contains() {
  local needle="$1"
  local file="$2"
  if grep -Fq -- "$needle" "$file"; then
    fail "expected $file not to contain: $needle"
  fi
  pass
}

print_pass_summary() {
  local label="$1"
  echo "PASS: $PASS_COUNT $label checks"
}
