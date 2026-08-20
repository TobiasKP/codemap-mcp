#!/usr/bin/env bash
# Scanner regression checks.
#
# Scans tools/fixtures/split and asserts the invariants that were easy to get wrong and
# invisible in the output: a class split across a header and an implementation file in a
# different folder is ONE node, a name shared across languages never becomes an edge, and
# a dependency declared in a build file becomes a module edge.
#
#   tools/graph-checks.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="${TMPDIR:-/tmp}/codemap-checks.db"
FIXTURE="$ROOT/tools/fixtures/split"

failures=0
check() {
  local label=$1 expected=$2 actual=$3
  if [ "$expected" = "$actual" ]; then
    printf '  ok   %-52s (%s)\n' "$label" "$actual"
  else
    printf ' FAIL  %-52s expected %s, got %s\n' "$label" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

q() { sqlite3 "$DB" "$1"; }

echo
echo "scanner checks against tools/fixtures/split"
echo
"$ROOT/run.sh" "$FIXTURE" --name checks --db "$DB" > "${DB%.db}.log" 2>&1 || {
  echo "scan failed; see ${DB%.db}.log"; exit 1; }

# A header/implementation pair in different folders is one class, not a class plus a file.
check "Facade is a single node" 1 \
  "$(q "SELECT count(*) FROM nodes WHERE layer=3 AND name='Facade';")"
check "no node for the implementation file" 0 \
  "$(q "SELECT count(*) FROM nodes WHERE layer=3 AND name LIKE 'Facade.cpp';")"
check "Facade is declared by its header" "engine/include/Facade.hpp" \
  "$(q "SELECT path FROM nodes WHERE layer=3 AND name='Facade';")"
# the header body is 8 lines; the implementation adds 13 more
check "Facade absorbs the implementation's lines" 1 \
  "$(q "SELECT CASE WHEN loc > 12 THEN 1 ELSE 0 END FROM nodes WHERE layer=3 AND name='Facade';")"

# The field and the calls in the .cpp both have to reach Helper.
check "Facade -> Helper edge exists" 1 \
  "$(q "SELECT count(*) FROM edges e
        JOIN nodes a ON a.id=e.src_id JOIN nodes b ON b.id=e.dst_id
        WHERE e.layer=3 AND a.name='Facade' AND b.name='Helper' AND b.lang='cpp';")"
# The edge carries every fact behind it: the include, the field, and the three calls on
# that field. The calls are the interesting part - they sit in the .cpp while `helper` is
# declared in the header, so they only resolve because each type publishes its field types
# for any file that implements it.
check "the edge records the include, the field and the calls" "TYPE_REF:1,FIELD:1,CALL:3" \
  "$(q "SELECT e.breakdown FROM edges e
        JOIN nodes a ON a.id=e.src_id JOIN nodes b ON b.id=e.dst_id
        WHERE e.layer=3 AND a.name='Facade' AND b.name='Helper' AND b.lang='cpp';")"

# The two files stay individually visible on the one node.
# one line per file, so newlines + 1 is the count
check "Facade lists both of its files" 2 \
  "$(q "SELECT length(files) - length(replace(files, char(10), '')) + 1
        FROM nodes WHERE layer=3 AND name='Facade';")"
check "the header is labelled a declaration" 1 \
  "$(q "SELECT CASE WHEN files LIKE 'declaration%Facade.hpp%' THEN 1 ELSE 0 END
        FROM nodes WHERE layer=3 AND name='Facade';")"
check "the .cpp is labelled an implementation" 1 \
  "$(q "SELECT CASE WHEN files LIKE '%implementation%Facade.cpp%' THEN 1 ELSE 0 END
        FROM nodes WHERE layer=3 AND name='Facade';")"

# Helper.js shares a name with the C++ Helper and must never be confused with it.
check "the JS namesake is its own node" 1 \
  "$(q "SELECT count(*) FROM nodes WHERE layer=3 AND name='Helper' AND lang='javascript';")"
check "no edge crosses language families" 0 \
  "$(q "SELECT count(*) FROM edges e
        JOIN nodes a ON a.id=e.src_id JOIN nodes b ON b.id=e.dst_id
        WHERE e.layer=3 AND a.lang<>b.lang;")"

# target_link_libraries(engine PUBLIC util) has to show up on layer 1.
check "declared cmake dependency became a module edge" 1 \
  "$(q "SELECT count(*) FROM edges e
        JOIN nodes a ON a.id=e.src_id JOIN nodes b ON b.id=e.dst_id
        WHERE e.layer=1 AND a.name='engine' AND b.name='util';")"

# Layer 4: the callables inside a class, and the calls between them. Facade::run and
# Facade::alsoHere are defined only in the .cpp, so this also checks that a member is
# attributed to the type its header declares rather than to the file it is written in.
check "Facade's callables are on layer 4" 2 \
  "$(q "SELECT count(*) FROM nodes m JOIN nodes t ON t.id=m.parent_id
        WHERE m.layer=4 AND t.name='Facade';")"
check "a method defined in the .cpp still belongs to the header's class" 1 \
  "$(q "SELECT count(*) FROM nodes m JOIN nodes t ON t.id=m.parent_id
        WHERE m.layer=4 AND m.name='alsoHere' AND t.name='Facade' AND t.lang='cpp';")"
check "the method's body length came from the .cpp" 1 \
  "$(q "SELECT CASE WHEN loc >= 3 THEN 1 ELSE 0 END FROM nodes
        WHERE layer=4 AND name='run';")"
check "run calls doIt twice" 2 \
  "$(q "SELECT e.weight FROM edges e JOIN nodes a ON a.id=e.src_id JOIN nodes b ON b.id=e.dst_id
        WHERE e.layer=4 AND a.name='run' AND b.name='doIt' AND b.lang='cpp';")"
check "alsoHere calls doIt once" 1 \
  "$(q "SELECT e.weight FROM edges e JOIN nodes a ON a.id=e.src_id JOIN nodes b ON b.id=e.dst_id
        WHERE e.layer=4 AND a.name='alsoHere' AND b.name='doIt' AND b.lang='cpp';")"
check "the JS namesake's method is never a call target" 0 \
  "$(q "SELECT count(*) FROM edges e JOIN nodes b ON b.id=e.dst_id
        WHERE e.layer=4 AND b.lang='javascript';")"
check "no callable is orphaned from its type" 0 \
  "$(q "SELECT count(*) FROM nodes m WHERE m.layer=4
        AND NOT EXISTS (SELECT 1 FROM nodes t WHERE t.layer=3 AND t.id=m.parent_id);")"

# No package should survive with nothing in it.
check "no empty packages" 0 \
  "$(q "SELECT count(*) FROM nodes p WHERE p.layer=2
        AND NOT EXISTS (SELECT 1 FROM nodes t WHERE t.layer=3 AND t.parent_id=p.id);")"

# Every edge must join two nodes that exist, on the same layer.
check "no dangling edges" 0 \
  "$(q "SELECT count(*) FROM edges e
        LEFT JOIN nodes a ON a.id=e.src_id LEFT JOIN nodes b ON b.id=e.dst_id
        WHERE a.id IS NULL OR b.id IS NULL OR a.layer<>e.layer OR b.layer<>e.layer;")"
check "no self edges" 0 "$(q "SELECT count(*) FROM edges WHERE src_id=dst_id;")"

echo
if [ "$failures" -eq 0 ]; then
  echo "all scanner checks passed"
else
  echo "$failures scanner check(s) FAILED"
fi
exit "$((failures > 0))"
