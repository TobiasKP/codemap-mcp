#!/usr/bin/env bash
# Dependency-free build: compiles straight against the vendored jars in lib/.
# Use this when Maven cannot write to ~/.m2 (sandboxes, CI without a warm cache).
# `mvn package` works too and produces the same thing in target/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/out"
CP="$(find "$ROOT/lib" -name '*.jar' | sort | tr '\n' ':')"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

find "$ROOT/src/main/java" -name '*.java' > "$OUT/sources.txt"
echo "compiling $(wc -l < "$OUT/sources.txt") sources..."
javac -nowarn -encoding UTF-8 --release 21 \
      -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt"

cp -r "$ROOT/src/main/resources/." "$OUT/classes/"

echo "ok -> $OUT/classes"
