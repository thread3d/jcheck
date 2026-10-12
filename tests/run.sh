#!/usr/bin/env bash
# Builds jcheck and its tests, then runs everything. Pass --bless to regenerate UI goldens.
set -euo pipefail
cd "$(dirname "$0")/.."

./build.sh > /dev/null

rm -rf out/test-classes
mkdir -p out/test-classes
find tests/java -name '*.java' | sort > out/test-sources.txt
javac -cp out/classes -d out/test-classes -encoding UTF-8 @"out/test-sources.txt"

java -cp out/classes:out/test-classes jcheck.tests.TestMain "$PWD" "$@"
