#!/usr/bin/env bash
# Compiles src/ into a throw-away temp dir (nothing is written into the repo) and runs the tests.
set -euo pipefail
cd "$(dirname "$0")"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" src/orders/*.java
java -cp "$OUT" orders.Tests
