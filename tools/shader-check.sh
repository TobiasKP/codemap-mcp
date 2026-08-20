#!/usr/bin/env bash
# Compiles every shader in web/app.js with glslangValidator.
#
# The stub-DOM harness accepts any string as shader source, so a typo in GLSL shows up as
# a blank canvas in a browser and as a passing test here. This is the check that catches
# it. Run it after touching either program.
#
#   tools/shader-check.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/gs-shaders"
APP="$ROOT/src/main/resources/web/app.js"

command -v glslangValidator > /dev/null || {
  echo "glslangValidator not installed; skipping shader check"; exit 0; }

rm -rf "$OUT"; mkdir -p "$OUT"

# GLSL lives in template literals starting with the #version line; a vertex shader is the
# one declaring attributes, a fragment shader the one declaring an out colour.
node - "$APP" "$OUT" <<'JS'
const fs = require('node:fs');
const [, , app, out] = process.argv;
const src = fs.readFileSync(app, 'utf8');
let count = 0;
for (let i = src.indexOf('`#version'); i >= 0; i = src.indexOf('`#version', i + 1)) {
  const end = src.indexOf('`', i + 1);
  if (end < 0) break;
  const body = src.slice(i + 1, end);
  const stage = /\bgl_Position\b/.test(body) ? 'vert' : 'frag';
  fs.writeFileSync(`${out}/shader${count++}.${stage}`, body);
}
console.log(count);
JS

failures=0
for file in "$OUT"/*.vert "$OUT"/*.frag; do
  [ -e "$file" ] || continue
  if glslangValidator "$file" > "$file.log" 2>&1; then
    printf '  ok   %s\n' "$(basename "$file")"
  else
    printf ' FAIL  %s\n' "$(basename "$file")"
    sed 's/^/       /' "$file.log"
    failures=$((failures + 1))
  fi
done

echo
if [ "$failures" -eq 0 ]; then echo "all shaders compile"; else echo "$failures shader(s) failed"; fi
exit $((failures > 0))
