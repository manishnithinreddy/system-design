#!/usr/bin/env sh
# Compile and run tests + demo. Requires Java 21+.
set -e
cd "$(dirname "$0")"
rm -rf out
javac -d out src/editor/*.java
echo "== Tests =="
java -cp out editor.EditorTests
echo
echo "== Demo =="
java -cp out editor.Demo
