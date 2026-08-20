#!/usr/bin/env bash
# Runs codemap from the build.sh output. Builds first if needed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CP="$(find "$ROOT/lib" -name '*.jar' | sort | tr '\n' ':')$ROOT/out/classes"

[ -d "$ROOT/out/classes" ] || "$ROOT/build.sh"

# tree-sitter unpacks its JNI natives through java.io.tmpdir; Main re-points it if
# it is not writable, but honouring TMPDIR here keeps sandboxes happy from the start.
TMP="${TMPDIR:-/tmp}"
exec java -Xss16m -Djava.io.tmpdir="$TMP" -cp "$CP" io.github.tobiaskp.codemap.Main "$@"
