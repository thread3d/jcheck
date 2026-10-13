#!/usr/bin/env bash
# Builds jcheck with nothing but a JDK: no Gradle, no Maven, no network.
#
#   ./build.sh          compile + jar
#   ./build.sh clean    remove out/
set -euo pipefail
cd "$(dirname "$0")"

OUT="out/classes"
JAR="out/jcheck.jar"
SUPPORT_OUT="out/support-classes"
SUPPORT_JAR="out/jcheck-support.jar"

if [ "${1:-}" = "clean" ]; then
  rm -rf out
  echo "cleaned out/"
  exit 0
fi

if ! command -v javac >/dev/null 2>&1; then
  echo "build.sh: javac not found; jcheck must be built with a JDK, not a JRE" >&2
  exit 2
fi

# jcheck's own source needs records, switch expressions and Unix domain sockets, so JDK 17 is the
# floor. Java 8 and 11 projects are supported as --release targets, not as the building JDK.
JAVAC_MAJOR="$(javac -version 2>&1 | sed -nE 's/^javac ([0-9]+).*/\1/p')"
if [ "$JAVAC_MAJOR" = "1" ]; then
  # JDK 8 and earlier report as 1.8; the meaningful number is the second component.
  JAVAC_MAJOR="$(javac -version 2>&1 | sed -nE 's/^javac 1\.([0-9]+).*/\1/p')"
fi
if [ -n "$JAVAC_MAJOR" ] && [ "$JAVAC_MAJOR" -lt 17 ]; then
  echo "build.sh: jcheck needs JDK 17 or newer to build (found javac $JAVAC_MAJOR)." >&2
  echo "           Java 8 and 11 projects are checked as --release targets instead." >&2
  exit 2
fi

rm -rf "$OUT"
mkdir -p "$OUT"

find src/main/java -name '*.java' | sort > out/sources.txt
javac -d "$OUT" -encoding UTF-8 @"out/sources.txt"

# jcheck-support is a separate artifact: the annotations the check pack recognizes by name, plus
# the Result and Todo helpers the strict profile expects. A project adds it as a dependency.
if [ -d support/java ]; then
  rm -rf "$SUPPORT_OUT"
  mkdir -p "$SUPPORT_OUT"
  find support/java -name '*.java' | sort > out/support-sources.txt
  javac -d "$SUPPORT_OUT" -encoding UTF-8 @"out/support-sources.txt"
  jar --create --file "$SUPPORT_JAR" -C "$SUPPORT_OUT" .
fi

# Catalog data and bundled explain pages travel with the classes.
cp -R src/main/resources/. "$OUT"/
mkdir -p "$OUT/jcheck/docs"
cp docs/*.md "$OUT/jcheck/docs/"

jar --create --file "$JAR" --main-class jcheck.cli.Main -C "$OUT" .

echo "built $(find "$OUT" -name '*.class' | wc -l | tr -d ' ') classes -> $JAR"
if [ -f "$SUPPORT_JAR" ]; then
  echo "built jcheck-support -> $SUPPORT_JAR"
fi
