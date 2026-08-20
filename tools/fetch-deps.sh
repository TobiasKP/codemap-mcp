#!/usr/bin/env bash
# Downloads the runtime dependencies into lib/, so build.sh works without Maven.
#
# The jars are not committed: they are 27 MB of third-party binaries, and the terms they
# carry are the upstream projects' to state, not this repository's to restate (see
# THIRD-PARTY.md). Fetch once, then build.sh is offline forever after.
#
# If you have Maven, `mvn package` does all of this for you and you can ignore this script.
#
#   tools/fetch-deps.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="$ROOT/lib"
REPO="${MAVEN_CENTRAL:-https://repo1.maven.org/maven2}"

# group:artifact:version — kept in step with pom.xml
DEPS=(
  "org/xerial:sqlite-jdbc:3.53.2.1"
  "io/github/bonede:tree-sitter:0.26.6"
  "io/github/bonede:tree-sitter-java:0.23.5"
  "io/github/bonede:tree-sitter-cpp:0.23.4"
  "io/github/bonede:tree-sitter-c:0.24.1"
  "io/github/bonede:tree-sitter-c-sharp:0.23.1"
  "io/github/bonede:tree-sitter-python:0.25.0"
  "io/github/bonede:tree-sitter-javascript:0.25.0"
  "io/github/bonede:tree-sitter-typescript:0.23.2"
  "io/github/bonede:tree-sitter-go:0.25.0"
  "io/github/bonede:tree-sitter-rust:0.24.0"
  "io/github/bonede:tree-sitter-kotlin:0.3.8.1"
  "io/github/bonede:tree-sitter-php:0.24.2"
  "io/github/bonede:tree-sitter-ruby:0.23.1"
  "io/github/bonede:tree-sitter-scala:0.24.0"
  "io/github/bonede:tree-sitter-swift:0.5.0"
)

mkdir -p "$LIB"
for dep in "${DEPS[@]}"; do
  group="${dep%%:*}"
  rest="${dep#*:}"
  artifact="${rest%%:*}"
  version="${rest##*:}"
  jar="$artifact-$version.jar"
  if [ -f "$LIB/$jar" ]; then
    printf '  have %s\n' "$jar"
    continue
  fi
  printf '  get  %s\n' "$jar"
  curl -fsSL "$REPO/$group/$artifact/$version/$jar" -o "$LIB/$jar.part"
  mv "$LIB/$jar.part" "$LIB/$jar"
done

printf 'ok -> %s (%s)\n' "$LIB" "$(du -sh "$LIB" | cut -f1)"
