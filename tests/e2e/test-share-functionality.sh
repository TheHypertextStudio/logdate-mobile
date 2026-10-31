#!/bin/bash

#
# Share Functionality E2E Runner
#
# Runs Android share UX instrumentation coverage on Gradle Managed Devices.
#
# Usage:
#   ./tests/e2e/test-share-functionality.sh [options]
#
# Examples:
#   ./tests/e2e/test-share-functionality.sh
#   ./tests/e2e/test-share-functionality.sh --verbose
#

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
GRAY='\033[0;37m'
NC='\033[0m'

VERBOSE=false

print_info() {
  echo -e "${BLUE}INFO${NC} $1"
}

print_success() {
  echo -e "${GREEN}PASS${NC} $1"
}

print_fail() {
  echo -e "${RED}FAIL${NC} $1"
}

usage() {
  cat << EOF
Usage: ./tests/e2e/test-share-functionality.sh [options]

Options:
  --verbose      Show executed commands
  --help         Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --verbose)
      VERBOSE=true
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      print_fail "Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

print_info "Running Android share UX e2e tests on Gradle Managed Devices"

CMD=(./run test:android:share)
if [ "$VERBOSE" = true ]; then
  CMD+=(--info)
  echo -e "${GRAY}$ ${CMD[*]}${NC}"
fi

"${CMD[@]}"

print_success "Share UX instrumentation suite passed on Gradle Managed Devices"
