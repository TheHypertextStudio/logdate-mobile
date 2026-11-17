#!/usr/bin/env bash
# The passkey verifier venv and the Node packages of the AT Protocol reference tools (the lexicon
# checker in scripts/tests/run-all.sh and the repository verifier in the atproto-repo JVM tests).
# The commit formatters need no step here: ./run format installs its pinned versions on first use.

TEST_DEPS_VENV="$LOGDATE_REPO_ROOT/scripts/passkey-verify/.venv"
TEST_DEPS_REQUIREMENTS="$LOGDATE_REPO_ROOT/scripts/passkey-verify/requirements.txt"
TEST_DEPS_NODE_TOOLS=(tools/lexicon-check tools/atproto-repo-verify)
# The oldest Node.js the pinned @atproto packages support.
TEST_DEPS_NODE_MAJOR=22

test_deps_describe() {
    printf 'Tests can run: the passkey verifier venv, Node.js %s or newer and the AT Protocol reference tools are installed' \
        "$TEST_DEPS_NODE_MAJOR"
}

test_deps_check() {
    local drift=0 tool
    if [[ ! -x "$TEST_DEPS_VENV/bin/python" ]]; then
        printf 'No passkey verifier venv at %s\n' "${TEST_DEPS_VENV#"$LOGDATE_REPO_ROOT"/}"
        drift=1
    elif ! "$TEST_DEPS_VENV/bin/python" -c 'import cryptography, fido2, requests' 2>/dev/null; then
        printf 'The passkey verifier venv is missing packages from requirements.txt\n'
        drift=1
    fi
    test_deps_check_node || drift=1
    for tool in "${TEST_DEPS_NODE_TOOLS[@]}"; do
        if [[ ! -d "$LOGDATE_REPO_ROOT/$tool/node_modules/@atproto" ]]; then
            printf 'The packages in %s are not installed\n' "$tool"
            drift=1
        fi
    done
    return "$drift"
}

# Node.js has to be new enough for the pinned @atproto packages. pnpm itself is optional: without
# it, the version a tool's package.json pins runs through npx.
test_deps_check_node() {
    if ! command -v node >/dev/null 2>&1; then
        printf 'Node.js is not installed (needs %s or newer)\n' "$TEST_DEPS_NODE_MAJOR"
        return 1
    fi
    local major
    major="$(node -p 'process.versions.node.split(".")[0]')"
    if (( major < TEST_DEPS_NODE_MAJOR )); then
        printf 'Node.js %s is too old; the AT Protocol reference tools need %s or newer\n' "$major" "$TEST_DEPS_NODE_MAJOR"
        return 1
    fi
    command -v pnpm >/dev/null 2>&1 || command -v npx >/dev/null 2>&1 || {
        printf 'Neither pnpm nor npx is on the PATH\n'
        return 1
    }
}

test_deps_apply() {
    command -v python3 >/dev/null 2>&1 || { printf 'Install Python 3, then re-run.\n'; return 2; }
    [[ -x "$TEST_DEPS_VENV/bin/python" ]] || python3 -m venv "$TEST_DEPS_VENV"
    "$TEST_DEPS_VENV/bin/pip" install --quiet -r "$TEST_DEPS_REQUIREMENTS"
    test_deps_check_node >/dev/null || {
        test_deps_check_node
        printf 'Install Node.js %s or newer (brew install node), then re-run.\n' "$TEST_DEPS_NODE_MAJOR"
        return 2
    }
    local tool
    for tool in "${TEST_DEPS_NODE_TOOLS[@]}"; do
        test_deps_install_node_tool "$LOGDATE_REPO_ROOT/$tool" || return
    done
}

# Installs one tool's locked packages with the same script the atproto-repo Gradle build uses. It
# runs pnpm when it's installed, and otherwise the pnpm version the tool's package.json pins,
# through npx.
test_deps_install_node_tool() {
    "$LOGDATE_REPO_ROOT/tools/atproto-repo-verify/install.sh" "$1"
}
