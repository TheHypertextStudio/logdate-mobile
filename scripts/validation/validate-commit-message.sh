#!/usr/bin/env bash
# validate-commit-message.sh - Validates a commit message.
#
# Usage:
#   validate-commit-message.sh --file <path> [--context <context>]
#   validate-commit-message.sh --message "<text>" [--context <context>]
#
# Contexts:
#   commit-msg (default)  - includes warning output for imperative mood
#   pre-push              - hard checks only
#   checklist             - hard checks only

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$REPO_ROOT/scripts/validation/hook-common.sh"

context="commit-msg"
message=""
message_file=""
MAX_BODY_LINE_LENGTH=72

while [[ $# -gt 0 ]]; do
  case "$1" in
    --context)
      context="${2:-}"
      shift 2
      ;;
    --message)
      message="${2:-}"
      shift 2
      ;;
    --file)
      message_file="${2:-}"
      shift 2
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -n "$message" && -n "$message_file" ]]; then
  echo "ERROR: Use either --message or --file, not both." >&2
  exit 1
fi

if [[ -z "$message" && -z "$message_file" ]]; then
  echo "ERROR: Must provide --message or --file." >&2
  exit 1
fi

if [[ -n "$message_file" ]]; then
  if [[ ! -f "$message_file" ]]; then
    echo "ERROR: Commit message file not found: $message_file" >&2
    exit 1
  fi
  message="$(cat "$message_file")"
fi

if [[ -z "$message" ]]; then
  echo "ERROR: Commit message is empty." >&2
  exit 1
fi

title="${message%%$'\n'*}"
errors=()

# Skip merge/fixup/squash commits.
if [[ "$title" =~ ^Merge ]] || [[ "$title" =~ ^fixup! ]] || [[ "$title" =~ ^squash! ]]; then
  exit 0
fi

if ! echo "$title" | grep -qE "^($VALID_TYPES)(\([^)]+\))?!?: .+"; then
  errors+=("FORMAT: Commit title must follow: type(scope): description")
  errors+=("  Valid types: feat, fix, chore (the studio commit standard)")
fi

title_len=${#title}
if [[ $title_len -gt 72 ]]; then
  errors+=("LENGTH: Title is $title_len chars (max 72).")
fi

is_footer_line() {
  local line="$1"
  [[ "$line" =~ ^(BREAKING[[:space:]]CHANGE:|[A-Za-z][A-Za-z-]*:|Fixes[[:space:]]#|Closes[[:space:]]#|Related[[:space:]]to[[:space:]]#|Co-Authored-By:|Co-authored-by:) ]]
}

is_fence_line() {
  local line="$1"
  local trimmed="${line#"${line%%[![:space:]]*}"}"
  [[ "$trimmed" == '```'* || "$trimmed" == '~~~'* ]]
}

line_number=1
in_fence=0
while IFS= read -r line; do
  line_number=$((line_number + 1))
  line="${line%$'\r'}"

  if is_fence_line "$line"; then
    if [[ $in_fence -eq 0 ]]; then
      in_fence=1
    else
      in_fence=0
    fi
    continue
  fi

  if [[ -z "$line" ]] || [[ "$line" == \#* ]]; then
    continue
  fi

  if [[ $in_fence -eq 1 ]]; then
    continue
  fi

  if is_footer_line "$line"; then
    continue
  fi

  line_len=${#line}
  if [[ $line_len -gt $MAX_BODY_LINE_LENGTH ]]; then
    errors+=("LENGTH: Body line $line_number is $line_len chars (max $MAX_BODY_LINE_LENGTH).")
  fi
done < <(tail -n +2 <<< "$message")

if echo "$title" | grep -qE "^($VALID_TYPES)\([^)]+\)!?: .+"; then
  scope="$(echo "$title" | sed -E "s/^($VALID_TYPES)\(([^)]+)\)!?: .*/\2/")"
  if ! scope_output="$("$REPO_ROOT/scripts/validation/validate-commit-scope.sh" "$scope" 2>&1)"; then
    if [[ "$context" == "commit-msg" && -n "$scope_output" ]]; then
      echo "$scope_output"
    fi
    errors+=("SCOPE: Invalid scope '$scope'.")
  fi
fi

if grep -qiE "phase[[:space:]]+[0-9]" <<< "$message"; then
  errors+=("PHASE: Phase numbers are forbidden in commit messages.")
fi

if [[ "$context" == "commit-msg" ]]; then
  desc="$(echo "$title" | sed -E "s/^($VALID_TYPES)(\([^)]+\))?!?: //")"
  if echo "$desc" | grep -qE "^(Added|Fixed|Updated|Removed|Changed|Implemented|Created|Deleted|Moved|Renamed) "; then
    first_word="$(echo "$desc" | awk '{print $1}')"
    imperative="$(echo "$first_word" | sed -E 's/ed$//; s/d$//')"
    imperative_cap="$(echo "$imperative" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"
    rest="$(echo "$desc" | sed "s/^$first_word //")"
    echo ""
    echo "WARNING: Use imperative mood in commit title."
    echo "  Instead of: \"$desc\""
    echo "  Try: \"$imperative_cap $rest\""
    echo ""
  fi
fi

if [[ ${#errors[@]} -gt 0 ]]; then
  err=""
  for err in "${errors[@]}"; do
    echo "$err"
  done
  exit 1
fi

exit 0
