#!/usr/bin/env sh
# Compile and run tests + demo. Requires Java 21+.
# -Dstdout.encoding=UTF-8 so the rupee sign prints correctly on terminals with a non-UTF-8 default.
set -e
cd "$(dirname "$0")"
rm -rf out
javac -d out src/wallet/*.java
echo "== Tests =="
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp out wallet.WalletTests
echo
echo "== Demo =="
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp out wallet.Demo
