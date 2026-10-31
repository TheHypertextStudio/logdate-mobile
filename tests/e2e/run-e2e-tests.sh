#!/bin/bash

#
# LogDate E2E Test Runner
#
# Convenient script for running end-to-end tests for LogDate
#
# Usage:
#   ./tests/e2e/run-e2e-tests.sh [test-type] [options]
#
# Examples:
#   ./tests/e2e/run-e2e-tests.sh multi-window              # Run multi-window editor tests
#   ./tests/e2e/run-e2e-tests.sh sharing                   # Run share UX tests
#   ./tests/e2e/run-e2e-tests.sh accounts                  # Run auth v1 tests
#   ./tests/e2e/run-e2e-tests.sh all                       # Run all e2e tests
#   ./tests/e2e/run-e2e-tests.sh multi-window --debug      # Run with verbose output
#

set -e

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test type (default: all)
TEST_TYPE="${1:-all}"
shift || true

# Options
VERBOSE=false
ENABLE_DEBUG=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --verbose)
      VERBOSE=true
      shift
      ;;
    --debug)
      ENABLE_DEBUG=true
      shift
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# Helper functions
print_banner() {
  echo -e "${BLUE}╔═══════════════════════════════════════════════════════════╗${NC}"
  echo -e "${BLUE}║${NC} LogDate E2E Test Suite $1"
  echo -e "${BLUE}╚═══════════════════════════════════════════════════════════╝${NC}"
}

print_success() {
  echo -e "${GREEN}✓${NC} $1"
}

print_error() {
  echo -e "${RED}✗${NC} $1"
}

print_info() {
  echo -e "${BLUE}ℹ${NC} $1"
}

print_warning() {
  echo -e "${YELLOW}⚠${NC} $1"
}

# Run test based on type
run_test() {
  case "$TEST_TYPE" in
    multi-window)
      print_banner "Multi-Window Editor Tests"

      print_info "Running multi-window editor e2e tests..."

      local cmd="./run test:android:multiwindow"

      if [ "$VERBOSE" = true ] || [ "$ENABLE_DEBUG" = true ]; then
        cmd="$cmd --info"
      fi

      eval "$cmd"
      print_success "Multi-window tests completed"
      ;;

    sharing)
      print_banner "Share UX Tests"

      print_info "Running share UX e2e tests..."

      local cmd="./tests/e2e/test-share-functionality.sh"

      if [ "$VERBOSE" = true ]; then
        cmd="$cmd --verbose"
      fi

      eval "$cmd"
      print_success "Share UX tests completed"
      ;;

    accounts)
      print_banner "Account Journey Tests"
      print_info "Running account creation and sign-in e2e tests..."

      local cmd="./gradlew :server:test --tests \"app.logdate.server.e2e.journeys.auth.AuthOnboardingAndSigninJourneyE2ETest\""

      if [ "$VERBOSE" = true ] || [ "$ENABLE_DEBUG" = true ]; then
        cmd="$cmd --info"
      fi

      eval "$cmd"
      print_success "Account tests completed"
      ;;

    auth)
      print_banner "Authentication Tests"
      print_info "Running authentication flow e2e tests..."

      local cmd="./gradlew :server:test --tests \"app.logdate.server.e2e.journeys.auth.*\""

      if [ "$VERBOSE" = true ] || [ "$ENABLE_DEBUG" = true ]; then
        cmd="$cmd --info"
      fi

      eval "$cmd"
      print_success "Authentication tests completed"
      ;;

    all)
      print_banner "All E2E Tests"

      print_info "Running all e2e tests (client + server)..."

      # Run server tests
      print_info "Running server e2e tests..."
      ./gradlew :server:test --tests "app.logdate.server.e2e.*" $([ "$VERBOSE" = true ] && echo "--info" || echo "")
      print_success "Server tests completed"

      # Run client tests
      print_info "Running client e2e tests..."
      ./run test:android:e2e $([ "$VERBOSE" = true ] && echo "--info" || echo "")
      print_success "Client tests completed"

      print_success "All e2e tests completed"
      ;;

    list)
      print_banner "Available E2E Tests"
      echo ""
      echo "Client-side tests (Android):"
      echo "  - multi-window    : Multi-window editor tests (managed devices)"
      echo "  - sharing         : Android share UX tests (managed devices)"
      echo ""
      echo "Server-side tests (Ktor):"
      echo "  - accounts        : Auth v1 e2e tests"
      echo "  - auth            : Authentication flow e2e tests"
      echo ""
      echo "Combined:"
      echo "  - all             : Run all client and server e2e tests"
      echo ""
      echo "Options:"
      echo "  --verbose         : Show detailed test output"
      echo "  --debug           : Enable debug output"
      echo ""
      ;;

    *)
      print_error "Unknown test type: $TEST_TYPE"
      echo ""
      echo "Available test types:"
      echo "  - multi-window    : Multi-window editor tests"
      echo "  - sharing         : Android share UX tests"
      echo "  - accounts        : Account management tests"
      echo "  - auth            : Authentication tests"
      echo "  - all             : All e2e tests"
      echo "  - list            : Show all available tests"
      echo ""
      echo "Run: ./tests/e2e/run-e2e-tests.sh list"
      exit 1
      ;;
  esac
}

# Main execution
main() {
  print_banner "Starting"
  echo ""

  # Show test type and options
  print_info "Test Type: $TEST_TYPE"
  [ "$VERBOSE" = true ] && print_info "Verbose Output: Enabled"
  [ "$ENABLE_DEBUG" = true ] && print_info "Debug Mode: Enabled"
  echo ""

  # Run the test
  run_test

  echo ""
  print_success "Done!"
}

# Run main
main
