#!/bin/sh
# Run the java-polycall test suite against the REAL installed libpolycall.
#
#   POLYCALL_LIBRARY=/opt/polycall/lib/libpolycall.so.1 \
#   POLYCALL_CLI=/opt/polycall/bin/polycall sh scripts/test.sh
#
# Exit status: 0 = all tests ran and passed, 1 = failure,
# 77 = SKIP (a required toolchain is missing; nothing was tested).
set -u
cd "$(dirname "$0")/.." || exit 1

skip() { echo "SKIP: $*"; exit 77; }

command -v java >/dev/null 2>&1 || skip "java not found (JDK 22+ required)"
command -v mvn >/dev/null 2>&1 || skip "mvn not found (Apache Maven 3.6.3+ required)"
JV=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p')
case "$JV" in
  1.*|9|1[0-9]|2[01]) skip "java $JV found; the Foreign Function & Memory API needs JDK 22+" ;;
esac

if [ -z "${POLYCALL_CLI:-}" ] && command -v polycall >/dev/null 2>&1; then
  POLYCALL_CLI=$(command -v polycall); export POLYCALL_CLI
fi

# clearly-labelled fake ABI-2 library for the loader's mismatch test only
if [ -z "${POLYCALL_TEST_FAKE_ABI2:-}" ] && command -v cc >/dev/null 2>&1; then
  mkdir -p target/fake
  if cc -shared -fPIC -o target/fake/libfake_polycall_abi2.so src/test/c/fake_polycall_abi2.c 2>/dev/null; then
    POLYCALL_TEST_FAKE_ABI2=$(pwd)/target/fake/libfake_polycall_abi2.so; export POLYCALL_TEST_FAKE_ABI2
  fi
fi

echo "java: $(java -version 2>&1 | head -n 1)"
echo "POLYCALL_LIBRARY=${POLYCALL_LIBRARY:-<platform default>} POLYCALL_CLI=${POLYCALL_CLI:-<none>}"
exec mvn -B -ntp test
