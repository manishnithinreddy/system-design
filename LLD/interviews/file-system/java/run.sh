#!/usr/bin/env sh
# Compile and run tests + demo. Requires Java 21+.
set -e
cd "$(dirname "$0")"
rm -rf out
javac -d out src/fs/*.java
echo "== Tests =="
java -cp out fs.FileSystemTests
echo
echo "== Demo =="
java -cp out fs.Demo
