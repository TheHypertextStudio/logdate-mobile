#!/usr/bin/env bash
# Shared helpers for git hook scripts.

ZERO_SHA="0000000000000000000000000000000000000000"
VALID_TYPES="feat|fix|refactor|docs|style|test|chore|perf"

MODULE_TASKS_CACHE_KEYS=()
MODULE_TASKS_CACHE_VALUES=()
RESOLVED_TEST_TASKS=()
UNRESOLVED_TEST_MODULES=()
PROJECT_DIR_OVERRIDE_PATHS=()
PROJECT_DIR_OVERRIDE_MODULES=()
PROJECT_DIR_OVERRIDES_LOADED=0

uniq_non_empty_lines() {
  awk 'NF && !seen[$0]++'
}

load_project_dir_overrides() {
  local repo_root=""
  local settings_file=""
  local line=""
  local module=""
  local path=""

  if [[ "$PROJECT_DIR_OVERRIDES_LOADED" -eq 1 ]]; then
    return 0
  fi

  PROJECT_DIR_OVERRIDE_PATHS=()
  PROJECT_DIR_OVERRIDE_MODULES=()

  repo_root="$(git rev-parse --show-toplevel 2> /dev/null || pwd)"
  settings_file="$repo_root/settings.gradle.kts"

  if [[ -f "$settings_file" ]]; then
    while IFS= read -r line; do
      module="${line%%|*}"
      path="${line#*|}"
      PROJECT_DIR_OVERRIDE_PATHS+=("${path%/}")
      PROJECT_DIR_OVERRIDE_MODULES+=("$module")
    done < <(sed -nE 's@^[[:space:]]*project\("(:[^"]+)"\)\.projectDir[[:space:]]*=[[:space:]]*file\("([^"]+)"\)[[:space:]]*$@\1|\2@p' "$settings_file")
  fi

  PROJECT_DIR_OVERRIDES_LOADED=1
}

module_name_from_dir() {
  local dir="$1"
  local idx=""

  load_project_dir_overrides

  for idx in "${!PROJECT_DIR_OVERRIDE_PATHS[@]}"; do
    if [[ "${PROJECT_DIR_OVERRIDE_PATHS[$idx]}" == "${dir%/}" ]]; then
      printf '%s\n' "${PROJECT_DIR_OVERRIDE_MODULES[$idx]}"
      return 0
    fi
  done

  printf ':%s\n' "$(echo "$dir" | tr '/' ':')"
}

modules_from_files() {
  local files="$1"
  local modules=""
  local file=""
  local dir=""
  local module=""

  while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    # Skip files in included builds (e.g. build-logic/, samples/atproto-consumer/) —
    # they are not subprojects of the main build, so :module:ktlintFormat tasks don't
    # exist for them.
    case "$file" in
      build-logic/*) continue ;;
      samples/atproto-consumer/*) continue ;;
    esac
    dir="$(dirname "$file")"
    while [[ "$dir" != "." && "$dir" != "/" ]]; do
      if [[ -f "$dir/build.gradle.kts" ]]; then
        module="$(module_name_from_dir "$dir")"
        modules="${modules}${module}"$'\n'
        break
      fi
      dir="$(dirname "$dir")"
    done
  done <<< "$files"

  echo "$modules" | uniq_non_empty_lines
}

collect_unpushed_commits() {
  local local_shas="$1"
  local all_commits=""
  local sha=""
  local commits=""

  while IFS= read -r sha; do
    [[ -z "$sha" ]] && continue
    commits="$(git rev-list "$sha" --not --remotes 2> /dev/null || true)"
    if [[ -n "$commits" ]]; then
      all_commits="${all_commits}${commits}"$'\n'
    fi
  done <<< "$local_shas"

  echo "$all_commits" | uniq_non_empty_lines
}

collect_changed_files_from_commits() {
  local commits="$1"
  local changed=""
  local sha=""
  local files=""

  while IFS= read -r sha; do
    [[ -z "$sha" ]] && continue
    files="$(git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR "$sha" 2> /dev/null || true)"
    if [[ -n "$files" ]]; then
      changed="${changed}${files}"$'\n'
    fi
  done <<< "$commits"

  echo "$changed" | uniq_non_empty_lines
}

fetch_module_tasks() {
  local module="$1"
  ./gradlew "${module}:tasks" --all --quiet 2> /dev/null || true
}

module_tasks_cache_get_or_load() {
  local module="$1"
  local idx=""
  local fetched_tasks=""

  for idx in "${!MODULE_TASKS_CACHE_KEYS[@]}"; do
    if [[ "${MODULE_TASKS_CACHE_KEYS[$idx]}" == "$module" ]]; then
      printf '%s\n' "${MODULE_TASKS_CACHE_VALUES[$idx]}"
      return 0
    fi
  done

  fetched_tasks="$(fetch_module_tasks "$module")"
  MODULE_TASKS_CACHE_KEYS+=("$module")
  MODULE_TASKS_CACHE_VALUES+=("$fetched_tasks")
  printf '%s\n' "$fetched_tasks"
}

module_has_task() {
  local tasks_output="$1"
  local task_name="$2"

  grep -Eq "^[[:space:]]*${task_name}([[:space:]-]|$)" <<< "$tasks_output"
}

resolve_test_task_for_module() {
  local module="$1"
  local coverage_mode="${2:-balanced}"
  local module_tasks=""
  local task=""
  local -a candidate_tasks=()

  case "$coverage_mode" in
    balanced)
      candidate_tasks=(
        "test"
        "testDebugUnitTest"
        "jvmTest"
        "testAndroidHostTest"
        "desktopTest"
      )
      ;;
    *)
      return 1
      ;;
  esac

  module_tasks="$(module_tasks_cache_get_or_load "$module")"
  for task in "${candidate_tasks[@]}"; do
    if module_has_task "$module_tasks" "$task"; then
      printf '%s:%s\n' "$module" "$task"
      return 0
    fi
  done

  return 1
}

resolve_test_tasks_for_modules() {
  local coverage_mode="$1"
  shift
  local module=""
  local resolved_task=""

  RESOLVED_TEST_TASKS=()
  UNRESOLVED_TEST_MODULES=()

  for module in "$@"; do
    [[ -z "$module" ]] && continue
    # Modules with no test sources (BOMs, version catalogs, etc) are
    # skipped here so they don't end up in UNRESOLVED_TEST_MODULES and
    # block the push.
    case "$module" in
      :shared:atproto-bom) continue ;;
    esac
    if resolved_task="$(resolve_test_task_for_module "$module" "$coverage_mode")"; then
      RESOLVED_TEST_TASKS+=("$resolved_task")
    else
      UNRESOLVED_TEST_MODULES+=("$module")
    fi
  done
}

print_error_report() {
  local title="$1"
  local footer="${2:-}"
  local err=""

  if [[ ${#errors[@]} -eq 0 ]]; then
    return
  fi

  echo ""
  echo "=========================================="
  echo "  $title"
  echo "=========================================="
  echo ""
  for err in "${errors[@]}"; do
    echo "  $err"
  done
  if [[ -n "$footer" ]]; then
    echo ""
    echo "  $footer"
  fi
  echo "=========================================="
  echo ""
}
