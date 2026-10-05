#!/bin/sh
# Build the Maven Central upload bundle with the JDK and gpg only (no Maven).
#
#   JAVA_HOME=/path/to/jdk-22+ sh scripts/central-bundle.sh
#   GPG_KEY=<fingerprint> ...       # sign with this key instead of gpg's default
#
# Output: target/central-bundle.zip, laid out as <groupId path>/<artifactId>/
# <version>/ with the jar, -sources.jar, -javadoc.jar and .pom, each with .asc,
# .md5 and .sha1. Upload it at https://central.sonatype.com/publishing
# (Publish Component), wait for VALIDATED, then press Publish.
# With Maven installed, `mvn -P release deploy` does the same (see pom.xml).
set -eu
cd "$(dirname "$0")/.." || exit 1

die() { echo "central-bundle: $*" >&2; exit 1; }

JDK=${JAVA_HOME:+$JAVA_HOME/bin/}
for t in javac jar javadoc; do
  command -v "$JDK$t" >/dev/null 2>&1 || die "$JDK$t not found (set JAVA_HOME to a JDK 22+)"
done
for t in gpg md5sum sha1sum; do
  command -v "$t" >/dev/null 2>&1 || die "$t not found"
done

# project coordinates and jar manifest, read from pom.xml (4-space indent = top level)
pom() { sed -n "s:^$1<$2>\(.*\)</$2>\$:\1:p" pom.xml | head -n 1; }
G=$(pom '    ' groupId); A=$(pom '    ' artifactId); V=$(pom '    ' version)
REL=$(pom '        ' maven.compiler.release)
MAIN=$(pom ' *' mainClass); MODULE=$(pom ' *' Automatic-Module-Name)
NATIVE=$(pom ' *' Enable-Native-Access)
[ -n "$G" ] && [ -n "$A" ] && [ -n "$V" ] && [ -n "$REL" ] || die "cannot read coordinates from pom.xml"
case "$V" in *-SNAPSHOT) die "$V: Maven Central does not accept SNAPSHOT versions" ;; esac

B=target/central
OUT=$B/$(echo "$G" | tr . /)/$A/$V
rm -rf "$B" target/central-bundle.zip
mkdir -p "$B/classes" "$B/javadoc" "$OUT"

echo "== $G:$A:$V (javac --release $REL)"
find src/main/java -name '*.java' > "$B/sources.txt"
"${JDK}javac" --release "$REL" -encoding UTF-8 -Xlint:all -d "$B/classes" @"$B/sources.txt"

printf 'Main-Class: %s\nAutomatic-Module-Name: %s\nEnable-Native-Access: %s\n' \
  "$MAIN" "$MODULE" "$NATIVE" > "$B/MANIFEST.MF"
"${JDK}jar" --create --file "$OUT/$A-$V.jar" --manifest "$B/MANIFEST.MF" -C "$B/classes" .
"${JDK}jar" --create --file "$OUT/$A-$V-sources.jar" -C src/main/java .

"${JDK}javadoc" --release "$REL" -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 \
  -quiet -Xdoclint:all,-missing -d "$B/javadoc" @"$B/sources.txt"
"${JDK}jar" --create --file "$OUT/$A-$V-javadoc.jar" -C "$B/javadoc" .

cp pom.xml "$OUT/$A-$V.pom"

for f in "$OUT/$A-$V.jar" "$OUT/$A-$V-sources.jar" "$OUT/$A-$V-javadoc.jar" "$OUT/$A-$V.pom"; do
  gpg --yes --armor --detach-sign ${GPG_KEY:+--local-user "$GPG_KEY"} --output "$f.asc" "$f"
  gpg --verify "$f.asc" "$f" 2>/dev/null || die "signature check failed for $f"
  md5sum "$f" | cut -d ' ' -f 1 > "$f.md5"
  sha1sum "$f" | cut -d ' ' -f 1 > "$f.sha1"
done

"${JDK}jar" --create --no-manifest --file target/central-bundle.zip -C "$B" "$(echo "$G" | cut -d . -f 1)"
echo "== target/central-bundle.zip"
"${JDK}jar" --list --file target/central-bundle.zip | grep -v '/$'
