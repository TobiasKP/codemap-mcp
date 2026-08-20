#!/usr/bin/env bash
# Builds dist/codemap.jar: one file, no build step, no classpath, `java -jar` and go.
#
# Everything is merged in - the compiled classes, the web assets, sqlite-jdbc and all 15
# tree-sitter grammars with their bundled native libraries for linux/mac/windows on x64 and
# arm64. That is the whole point: the audience for this installs things with one command,
# and "clone the repo, run build.sh, mind the classpath" loses most of them before they
# ever see the map.
#
#   tools/package.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
STAGE="$ROOT/out/stage"
JAR="$DIST/codemap.jar"

"$ROOT/build.sh" > /dev/null
rm -rf "$STAGE" "$JAR"
mkdir -p "$STAGE" "$DIST"

# classes and web assets first, so anything a dependency happens to ship under the same
# path cannot shadow ours
cp -r "$ROOT/out/classes/." "$STAGE/"

for jar in "$ROOT"/lib/*.jar; do
  ( cd "$STAGE" && unzip -qo "$jar" -x 'META-INF/*.SF' 'META-INF/*.DSA' 'META-INF/*.RSA' \
      'META-INF/MANIFEST.MF' 'module-info.class' 2>&1 | grep -v '^caution:' || true )
done
# a signature over merged content is invalid by definition, and a stale index breaks loading
rm -rf "$STAGE/META-INF/INDEX.LIST" "$STAGE/META-INF/"*.SF "$STAGE/META-INF/"*.DSA \
       "$STAGE/META-INF/"*.RSA

# the jar redistributes third-party binaries, so the notices travel inside it
cp "$ROOT/LICENSE" "$STAGE/META-INF/LICENSE"
cp "$ROOT/THIRD-PARTY.md" "$STAGE/META-INF/THIRD-PARTY.md"

cat > "$STAGE/META-INF/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Main-Class: io.github.tobiaskp.codemap.Main
Implementation-Title: codemap
EOF

( cd "$STAGE" && jar --create --file "$JAR" --manifest META-INF/MANIFEST.MF . )

printf 'ok -> %s (%s)\n' "$JAR" "$(du -h "$JAR" | cut -f1)"
echo 'smoke test:'
java -jar "$JAR" --help | head -4 | sed 's/^/  /'
