#!/usr/bin/env bash
# MCP end-to-end checks.
#
# Starts a map server on a scratch graph, drives the stdio MCP server through a whole
# session - initialize, tools/list, reads, a proposal built up call by call - and then
# asserts on both halves: the JSON-RPC replies the model would see, and the overlay the
# browser would fetch. The invariant worth protecting is the last one: a proposal must
# never reach the database.
#
#   tools/mcp-checks.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="${TMPDIR:-/tmp}"
DB="$TMP/codemap-mcp.db"
FIXTURE="$ROOT/tools/fixtures/split"
PORT=${PORT:-7913}
SESSION="$TMP/codemap-mcp-session.jsonl"
REPLIES="$TMP/codemap-mcp-replies.jsonl"

failures=0
check() {
  local label=$1 expected=$2 actual=$3
  if [ "$expected" = "$actual" ]; then
    printf '  ok   %-54s (%s)\n' "$label" "$actual"
  else
    printf ' FAIL  %-54s expected %s, got %s\n' "$label" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

echo
echo "mcp checks on port $PORT"
echo

[ -f "$DB" ] || "$ROOT/run.sh" "$FIXTURE" --name mcpchecks --db "$DB" > "$TMP/gs-mcp-scan.log" 2>&1 || {
  echo "scan failed; see $TMP/gs-mcp-scan.log"; exit 1; }

# run.sh, not a bare `java`: it points java.io.tmpdir at a writable place, which the
# sqlite driver needs to unpack its native library at all.
"$ROOT/run.sh" serve "$DB" --port "$PORT" > "$TMP/gs-mcp-serve.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT

for _ in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:$PORT/api/meta" > /dev/null 2>&1 && break
  sleep 0.2
done

# One session, in order: handshake, read the tree, then build a proposal that exercises
# every operation. Ids are not hard-coded - names resolve, which is the point of the
# forgiving `ref` argument.
cat > "$SESSION" <<'JSONL'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_tree","arguments":{"depth":3}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find","arguments":{"query":"Facade"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_node","arguments":{"ref":"Facade"}}}
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_relationships","arguments":{"ref":"Facade"}}}
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"start_proposal","arguments":{"title":"Split the facade"}}}
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"propose_add","arguments":{"parent":"Facade","name":"renderShadows","kind":"METHOD","note":"the shadow pass belongs here"}}}
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"propose_modify","arguments":{"target":"Facade","note":"delegate the shadow pass"}}}
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"propose_connection","arguments":{"from":"n1","to":"util/.#Helper","kind":"CALL","note":"the new method uses the helper"}}}
{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"propose_delete","arguments":{"target":"util/.#Helper","note":"folded into the facade"}}}
{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"annotate","arguments":{"target":"Facade","note":"only caller is main"}}}
{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"get_proposal","arguments":{}}}
{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"get_node","arguments":{"ref":"NoSuchClassAnywhere"}}}
{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"propose_modify","arguments":{"target":"Helper","note":"which one?"}}}
{"jsonrpc":"2.0","id":16,"method":"ping"}
{"jsonrpc":"2.0","id":"str-17","method":"tools/call","params":{"name":"get_children","arguments":{"ref":"engine"}}}
{"jsonrpc":"2.0","id":18,"method":"resources/list"}
{"jsonrpc":"2.0","id":19,"method":"tools/call","params":{"name":"highlight","arguments":{"targets":["Facade","util/.#Helper"],"note":"both ends of the call"}}}
JSONL

"$ROOT/run.sh" mcp --port "$PORT" < "$SESSION" > "$REPLIES" 2> "$TMP/gs-mcp-stderr.log"

# ------------------------------------------------------------------ the replies

read_json() { node -e "$1" "$REPLIES"; }

check "one reply per request, none for the notification" 19 "$(wc -l < "$REPLIES" | tr -d ' ')"

# Clients send string ids, and most of them probe resources/prompts after the handshake.
# Neither should knock the loop over or corrupt the id echo.
check "a string request id comes back as a string" true "$(read_json '
  const l = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  const m = l.map(JSON.parse).find((m) => m.id === "str-17");
  console.log(!!m && !m.result.isError);')"

check "an unsupported method is an error, not a crash" -32601 "$(read_json '
  const l = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  const m = l.map(JSON.parse).find((m) => m.id === 18);
  console.log(m.error.code);')"

check "stdout is pure JSON-RPC" ok "$(read_json '
  const lines = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  for (const l of lines) { const m = JSON.parse(l); if (m.jsonrpc !== "2.0") throw new Error(l); }
  console.log("ok");' 2>&1 | tail -1)"

check "initialize echoes the protocol version" 2025-06-18 "$(read_json '
  const l = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  console.log(JSON.parse(l[0]).result.protocolVersion);')"

check "initialize advertises tools" true "$(read_json '
  const l = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  console.log(!!JSON.parse(l[0]).result.capabilities.tools);')"

check "every requested tool is listed" 15 "$(read_json '
  const l = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  const names = JSON.parse(l[1]).result.tools.map((t) => t.name);
  const wanted = ["get_tree","get_node","get_children","get_relationships",
    "propose_add","propose_modify","propose_delete","propose_move","propose_connection",
    "annotate","highlight"];
  for (const w of wanted) if (!names.includes(w)) throw new Error("missing " + w);
  console.log(names.length);')"

check "every tool has a usable schema" ok "$(read_json '
  const l = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  for (const t of JSON.parse(l[1]).result.tools) {
    if (!t.description || t.description.length < 40) throw new Error(t.name + ": thin description");
    if (t.inputSchema.type !== "object") throw new Error(t.name + ": bad schema");
    for (const req of t.inputSchema.required || []) {
      if (!t.inputSchema.properties[req]) throw new Error(t.name + ": required " + req + " has no property");
    }
  }
  console.log("ok");' 2>&1 | tail -1)"

text() { read_json "
  const l = require('fs').readFileSync(process.argv[1], 'utf8').trim().split('\n');
  const m = l.map(JSON.parse).find((m) => m.id === $1);
  process.stdout.write(m.result.content[0].text);"; }

check "get_tree reaches the classes" 1 "$(text 3 | grep -c 'CLASS Facade')"
check "find locates a class by name" 1 "$(text 4 | grep -c 'CLASS engine/include#Facade')"
check "get_node reports the split files" 2 "$(text 5 | grep -cE '^  (declaration|implementation)')"
check "get_relationships names both directions" 2 \
  "$(text 6 | grep -cE '^(depends on|used by) \(')"

check "propose_add hands back a ref" 1 "$(text 8 | grep -c 'reference is n1')"
check "a proposed node can be an edge endpoint" 1 "$(text 10 | grep -c 'proposed connection')"
check "get_proposal lists every operation" 5 "$(text 13 | grep -cE '^  (add|modify|delete|connect|annotate) ')"
check "get_proposal names the proposal" 1 "$(text 13 | grep -c 'Split the facade')"
check "highlight takes a set of nodes at once" 1 "$(text 19 | grep -c 'Highlighted 2 node')"

check "an unresolvable ref is an error, with suggestions" true "$(read_json '
  const l = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  const m = l.map(JSON.parse).find((m) => m.id === 14);
  console.log(m.result.isError === true && /nothing called/.test(m.result.content[0].text));')"

# Two classes called Helper in different modules: guessing one of them would put the
# proposal on the wrong node silently, so the tool refuses and says which two it means.
check "an ambiguous name is refused, naming the candidates" true "$(read_json '
  const l = require("fs").readFileSync(process.argv[1], "utf8").trim().split("\n");
  const m = l.map(JSON.parse).find((m) => m.id === 15);
  const text = m.result.content[0].text;
  console.log(m.result.isError === true && /matches 2 nodes/.test(text)
    && /util/.test(text) && /web/.test(text));')"

# ----------------------------------------------------------------- the overlay

curl -fsS "http://127.0.0.1:$PORT/api/proposal" > "$TMP/gs-overlay.json"
overlay() { node -e "$1" "$TMP/gs-overlay.json"; }

check "the overlay is active" true "$(overlay '
  console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).active === 1);')"

check "the change list survived the round trip" 7 "$(overlay '
  console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).changes.length);')"

# The whole point of the rollup: a method-level proposal has to be visible from the top.
check "a module is lit even though nothing named one" true "$(overlay '
  const o = JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
  console.log(Object.values(o.nodes).some((n) => n.own === 0));')"

check "the directly named nodes are marked as their own" true "$(overlay '
  const o = JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
  console.log(Object.values(o.nodes).some((n) => n.own === 1 && n.s === "delete"));')"

check "mixed contents roll up to a change" true "$(overlay '
  const o = JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
  const rolled = Object.values(o.nodes).filter((n) => n.own === 0);
  console.log(rolled.some((n) => n.s === "modify" && n.add + n.delete > 0));')"

check "additions carry a parent to hang off" true "$(overlay '
  const o = JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
  console.log(o.additions.length === 1 && o.additions[0].parentId > 0);')"

check "connections keep the new node as a ref" true "$(overlay '
  const o = JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
  const c = o.connections[0];
  console.log(c.fromRef === "n1" && c.fromId === 0 && c.toId > 0);')"

check "every lit node has an ancestor chain" true "$(overlay '
  const o = JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
  console.log(Object.keys(o.nodes).every((id) => Array.isArray(o.chains[id])));')"

REV=$(overlay 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).revision);')
check "polling with the current revision is a no-op" 1 \
  "$(curl -fsS "http://127.0.0.1:$PORT/api/proposal?since=$REV" | grep -c '"unchanged":true')"

# ------------------------------------------------------- nothing was persisted

check "no proposal rows in nodes" 0 \
  "$(sqlite3 "$DB" "SELECT count(*) FROM nodes WHERE name='renderShadows';")"
check "no proposal rows in edges" "$(sqlite3 "$DB" "SELECT count(*) FROM edges;")" \
  "$(sqlite3 "$DB" "SELECT count(*) FROM edges;")"
check "no proposal table appeared" 3 \
  "$(sqlite3 "$DB" "SELECT count(*) FROM sqlite_master WHERE type='table';")"

curl -fsS -X DELETE "http://127.0.0.1:$PORT/api/proposal" > /dev/null
check "clearing empties the overlay" 0 \
  "$(curl -fsS "http://127.0.0.1:$PORT/api/proposal" | node -e '
     let s=""; process.stdin.on("data",(d)=>s+=d).on("end",()=>
       console.log(JSON.parse(s).changes.length));')"

echo
if [ "$failures" -eq 0 ]; then
  echo "all mcp checks passed"
else
  echo "$failures check(s) failed"
fi
exit $((failures > 0))
