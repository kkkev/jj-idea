#!/usr/bin/env bash
# Local gate to run before pushing to master (jj-idea-4gai). Reproduces what CI and the JetBrains
# Marketplace verifier would reject, so it isn't discovered after the push (0.8.11, 0.9.2).
#
#   1. ./gradlew check verifyPlugin   tests + ktlint on the compile target, and the Plugin Verifier on
#                                     every supported IDE; checkVerifierBaseline fails on any internal
#                                     API usage not in config/plugin-verifier/internal-api-baseline.txt
#   2. compile main + test sources against the older platforms (compile-only; CI's compat matrix
#      also runs tests there). Catches APIs that don't exist on the sinceBuild floor.
set -euo pipefail
cd "$(dirname "$0")/.."

FLOOR_PLATFORMS=(2025.1 2025.2)

step() {
    local name=$1
    shift
    local start=$SECONDS
    echo "==> $name"
    "$@"
    echo "<== $name ok ($((SECONDS - start))s)"
}

step "check + verifyPlugin" ./gradlew check verifyPlugin
for v in "${FLOOR_PLATFORMS[@]}"; do
    step "compile against $v" \
        ./gradlew compileKotlin compileTestKotlin -PplatformVersion="$v" -PbuildDirName="build-compat-$v"
done
echo "pre-push gate passed (${SECONDS}s)"
