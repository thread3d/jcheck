#!/usr/bin/env bash
# Builds jcheck with nothing but a JDK: no Gradle, no Maven, no network.
#
#   ./build.sh          compile + jar
#   ./build.sh clean    remove out/
set -euo pipefail
cd "$(dirname "$0")"

OUT="out/classes"
JAR="out/jcheck.jar"

if [ "${1:-}" = "clean" ]; then
  rm -rf out
  echo "cleaned out/"
  exit 0
fi

if ! command -v javac >/dev/null 2>&1; then
  echo "build.sh: javac not found; jcheck must be built with a JDK, not a JRE" >&2
  exit 2
fi

rm -rf "$OUT"
mkdir -p "$OUT"

find src/main/java -name '*.java' | sort > out/sources.txt
javac -d "$OUT" -encoding UTF-8 @"out/sources.txt"

# Catalog data and bundled explain pages travel with the classes.
cp -R src/main/resources/. "$OUT"/
mkdir -p "$OUT/jcheck/docs"
cp docs/*.md "$OUT/jcheck/docs/"

jar --create --file "$JAR" --main-class jcheck.cli.Main -C "$OUT" .

echo "built $(find "$OUT" -name '*.class' | wc -l | tr -d ' ') classes -> $JAR"
