#!/usr/bin/env sh
# Compile and run tests + demo. Requires Java 21+.
set -e
cd "$(dirname "$0")"
rm -rf out
javac -d out src/moviebooking/*.java
echo "== Tests =="
java -Dstdout.encoding=UTF-8 -cp out moviebooking.BookingTests
echo
echo "== Demo =="
java -Dstdout.encoding=UTF-8 -cp out moviebooking.Demo
