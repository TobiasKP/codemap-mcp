package io.github.tobiaskp.codemap.mcp;

import io.github.tobiaskp.codemap.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An MCP server over stdio, so an LLM can read the map and draw a change on it.
 *
 * <p>The point is to replace prose. Asked to plan a refactor, a model normally writes
 * "add a service in package x, have y call it, delete z" - which the reader then has to
 * hold in their head and locate in a codebase they may not know. With these tools it edits
 * the map instead: the proposal becomes a shape you can look at from the top view (which
 * module does this touch?) and drill into (which package, which class, which method), with
 * arrows for the connections it wants to create.
 *
 * <p>This process holds no state. It is a translator between MCP and the HTTP API of a
 * running {@code codemap serve}, which owns both the graph and the proposal overlay.
 * That split is deliberate: the viewer in the browser and the agent in the editor are then
 * looking at the same proposal, and restarting the agent's client does not lose it.
 *
 * <p><b>Nothing here writes to the database.</b> A proposal is an overlay in the map
 * server's memory; clearing it, or stopping the server, leaves the graph exactly as scanned.
 */
public final class McpServer {

    private static final String NAME = "codemap";
    private static final String VERSION = "0.1.0";
    /** Answered when the client does not ask for a particular protocol version. */
    private static final String DEFAULT_PROTOCOL = "2025-06-18";

    private final String baseUrl;
    private final HttpClient http;
    private final PrintStream out;
    private final PrintStream log;

    public McpServer(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
        this.out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, StandardCharsets.UTF_8);
        // stdout carries the protocol, so anything human-facing has to go to stderr
        this.log = System.err;
    }

    // ------------------------------------------------------------------ the loop

    public void serve() throws IOException {
        log.println("codemap mcp: talking to " + baseUrl);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                handle(Json.asMap(Json.parse(line)));
            } catch (RuntimeException e) {
                log.println("codemap mcp: " + e);
            }
        }
    }

    private void handle(Map<String, Object> request) {
        String method = str(request.get("method"));
        Object id = request.get("id");
        boolean notification = !request.containsKey("id") || id == null;

        switch (method) {
            case "initialize" -> {
                Map<String, Object> params = Json.asMap(request.get("params"));
                Object asked = params.get("protocolVersion");
                String version = asked instanceof String s && !s.isBlank() ? s : DEFAULT_PROTOCOL;
                StringBuilder sb = new StringBuilder("{");
                Json.field(sb, "protocolVersion", version);
                sb.append(",\"capabilities\":{\"tools\":{}},\"serverInfo\":{");
                Json.field(sb, "name", NAME);
                sb.append(',');
                Json.field(sb, "version", VERSION);
                sb.append("},");
                Json.field(sb, "instructions", INSTRUCTIONS);
                sb.append('}');
                respond(id, sb.toString());
            }
            case "notifications/initialized", "notifications/cancelled" -> {
                // notifications carry no id and expect no answer
            }
            case "ping" -> respond(id, "{}");
            case "tools/list" -> respond(id, Tools.listJson());
            case "tools/call" -> {
                Map<String, Object> params = Json.asMap(request.get("params"));
                String name = str(params.get("name"));
                Map<String, Object> args = Json.asMap(params.get("arguments"));
                try {
                    respond(id, toolResult(call(name, args), false));
                } catch (ToolError e) {
                    respond(id, toolResult(e.getMessage(), true));
                } catch (Exception e) {
                    respond(id, toolResult("failed: " + e, true));
                }
            }
            default -> {
                if (!notification) error(id, -32601, "unknown method: " + method);
            }
        }
    }

    /** A tool call that failed in a way the model should read and react to. */
    private static final class ToolError extends Exception {
        ToolError(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------- tools

    private String call(String name, Map<String, Object> args) throws Exception {
        return switch (name) {
            case "get_tree" -> renderTree(get("/api/tree?ref=" + enc(str(args.get("ref")))
                    + "&depth=" + intArg(args, "depth", 2)
                    + "&width=" + intArg(args, "width", 60)));
            case "get_node" -> renderNode(need(args, "ref"));
            case "get_children" -> renderChildren(need(args, "ref"));
            case "get_relationships" -> renderRelationships(need(args, "ref"),
                    str(args.get("direction")));
            case "find" -> renderFind(need(args, "query"));

            case "start_proposal" -> {
                Map<String, Object> body = Map.of("title", need(args, "title"));
                post("/api/proposal/start", body);
                yield "Proposal started: " + need(args, "title")
                        + "\nThe map is showing the code as it is. Every propose_* call from "
                        + "now on adds to this proposal.";
            }
            case "propose_add" -> {
                Map<String, Object> result = change(Map.of(
                        "op", "add",
                        "parent", need(args, "parent"),
                        "name", need(args, "name"),
                        "kind", str(args.get("kind")),
                        "note", str(args.get("note"))));
                yield "Added " + str(args.get("name")) + " inside " + str(args.get("parent"))
                        + " (green on the map).\nIts reference is "
                        + str(result.get("ref"))
                        + " - use that string to put things inside it or connect it up."
                        + summary(result);
            }
            case "propose_modify" -> {
                Map<String, Object> result = change(Map.of(
                        "op", "modify",
                        "target", need(args, "target"),
                        "note", need(args, "note")));
                yield "Marked " + str(args.get("target")) + " as changing (yellow)."
                        + summary(result);
            }
            case "propose_delete" -> {
                Map<String, Object> result = change(Map.of(
                        "op", "delete",
                        "target", need(args, "target"),
                        "note", str(args.get("note"))));
                yield "Marked " + str(args.get("target")) + " for deletion (red)."
                        + summary(result);
            }
            case "propose_move" -> {
                Map<String, Object> result = change(Map.of(
                        "op", "move",
                        "target", need(args, "target"),
                        "parent", need(args, "new_parent"),
                        "note", str(args.get("note"))));
                yield "Moving " + str(args.get("target")) + " into "
                        + str(args.get("new_parent")) + "." + summary(result);
            }
            case "propose_connection" -> {
                Map<String, Object> result = change(Map.of(
                        "op", "connect",
                        "from", need(args, "from"),
                        "to", need(args, "to"),
                        "edge_kind", str(args.get("kind")),
                        "note", str(args.get("note"))));
                yield "Drew " + str(args.get("from")) + " → " + str(args.get("to"))
                        + " as a proposed connection." + summary(result);
            }
            case "annotate" -> {
                Map<String, Object> result = change(Map.of(
                        "op", "annotate",
                        "target", need(args, "target"),
                        "note", need(args, "note")));
                yield "Noted on " + str(args.get("target")) + "." + summary(result);
            }
            case "highlight" -> {
                List<Object> targets = new ArrayList<>(listArg(args, "targets"));
                if (targets.isEmpty() && !str(args.get("target")).isEmpty()) {
                    targets.add(str(args.get("target")));
                }
                if (targets.isEmpty()) throw new ToolError("highlight needs 'targets'");
                Map<String, Object> result = change(Map.of(
                        "op", "highlight",
                        "targets", targets,
                        "note", str(args.get("note"))));
                yield "Highlighted " + targets.size() + " node(s)." + summary(result);
            }
            case "get_proposal" -> renderProposal();
            case "clear_proposal" -> {
                delete("/api/proposal");
                yield "Proposal cleared. The map is back to showing the code as it is.";
            }
            default -> throw new ToolError("no such tool: " + name);
        };
    }

    // --------------------------------------------------------------- rendering

    /*
     * Everything below turns the API's JSON into lines of text. That is on purpose: the
     * consumer is a language model, and a compact indented listing costs a fraction of the
     * tokens of the equivalent JSON while being easier to reason about. Layout fields
     * (x, y, r) are dropped entirely - they matter to the renderer and to nobody else.
     */

    private String renderTree(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Containment tree (id  kind  name  lines/children). ")
                .append("Use an id or a qualified name as the 'ref' of any other tool.\n");
        renderLevel(sb, listArg(data, "children"), 0);
        if (sb.length() < 120) sb.append("  (nothing here)\n");
        return sb.toString();
    }

    private void renderLevel(StringBuilder sb, List<Object> nodes, int indent) {
        for (Object item : nodes) {
            Map<String, Object> node = Json.asMap(item);
            sb.append("  ".repeat(indent + 1));
            sb.append('#').append(num(node.get("id"))).append(' ');
            sb.append(str(node.get("kind"))).append(' ');
            sb.append(str(node.get("name")));
            long loc = num(node.get("loc"));
            long children = num(node.get("children"));
            List<String> parts = new ArrayList<>();
            if (loc > 0) parts.add(loc + " lines");
            if (children > 0) parts.add(children + " inside");
            long in = num(node.get("in"));
            long outDeg = num(node.get("out"));
            if (in > 0 || outDeg > 0) parts.add(in + " in, " + outDeg + " out");
            if (!parts.isEmpty()) sb.append("  (").append(String.join(", ", parts)).append(')');
            sb.append('\n');
            List<Object> inside = listArg(node, "inside");
            if (!inside.isEmpty()) renderLevel(sb, inside, indent + 1);
            if (!inside.isEmpty() && children > inside.size()) {
                sb.append("  ".repeat(indent + 2))
                        .append("… and ").append(children - inside.size())
                        .append(" more (call get_children on #")
                        .append(num(node.get("id"))).append(")\n");
            }
        }
    }

    private String renderNode(String ref) throws Exception {
        Map<String, Object> data = get("/api/node?id=" + enc(idOf(ref)));
        Map<String, Object> node = Json.asMap(data.get("node"));
        if (node.isEmpty()) throw new ToolError("no such node: " + ref);
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(num(node.get("id"))).append(' ')
                .append(str(node.get("kind"))).append(' ').append(str(node.get("name")))
                .append('\n');
        sb.append("qname       ").append(str(node.get("qname"))).append('\n');
        sb.append("level       ").append(levelName(num(node.get("layer")))).append('\n');
        if (!str(node.get("lang")).isEmpty()) {
            sb.append("language    ").append(str(node.get("lang"))).append('\n');
        }
        if (!str(node.get("path")).isEmpty()) {
            sb.append("path        ").append(str(node.get("path"))).append('\n');
        }
        if (num(node.get("loc")) > 0) {
            sb.append("lines       ").append(num(node.get("loc"))).append('\n');
        }
        if (num(node.get("children")) > 0) {
            sb.append("contains    ").append(num(node.get("children")))
                    .append(" (get_children)\n");
        }
        sb.append("references  ").append(num(node.get("out"))).append(" out, ")
                .append(num(node.get("in"))).append(" in (get_relationships)\n");

        List<Object> files = listArg(node, "files");
        if (files.size() > 1) {
            sb.append("files\n");
            for (Object item : files) {
                Map<String, Object> file = Json.asMap(item);
                sb.append("  ").append(str(file.get("role"))).append("  ")
                        .append(num(file.get("lines"))).append(" lines  ")
                        .append(str(file.get("path"))).append('\n');
            }
        }
        List<Object> parents = listArg(data, "parents");
        if (!parents.isEmpty()) {
            sb.append("inside      ");
            List<String> chain = new ArrayList<>();
            for (Object item : parents) {
                Map<String, Object> p = Json.asMap(item);
                chain.add("#" + num(p.get("id")) + " " + str(p.get("name")));
            }
            sb.append(String.join(" < ", chain)).append('\n');
        }
        return sb.toString();
    }

    private String renderChildren(String ref) throws Exception {
        Map<String, Object> data = get("/api/children?ref=" + enc(ref));
        checkError(data);
        List<Object> children = listArg(data, "children");
        if (children.isEmpty()) return "Nothing inside " + ref + ".";
        StringBuilder sb = new StringBuilder("Inside " + ref + " (" + children.size() + "):\n");
        for (Object item : children) {
            Map<String, Object> node = Json.asMap(item);
            sb.append("  #").append(num(node.get("id"))).append(' ')
                    .append(str(node.get("kind"))).append(' ')
                    .append(str(node.get("name")));
            long loc = num(node.get("loc"));
            long inner = num(node.get("children"));
            if (loc > 0) sb.append("  ").append(loc).append(" lines");
            if (inner > 0) sb.append(", ").append(inner).append(" inside");
            sb.append('\n');
        }
        return sb.toString();
    }

    private String renderRelationships(String ref, String direction) throws Exception {
        Map<String, Object> data = get("/api/node?id=" + enc(idOf(ref)));
        Map<String, Object> node = Json.asMap(data.get("node"));
        if (node.isEmpty()) throw new ToolError("no such node: " + ref);
        boolean wantOut = !direction.equalsIgnoreCase("in");
        boolean wantIn = !direction.equalsIgnoreCase("out");
        StringBuilder sb = new StringBuilder(str(node.get("qname")) + "\n");
        if (wantOut) writeEdges(sb, "depends on", listArg(data, "out"));
        if (wantIn) writeEdges(sb, "used by", listArg(data, "in"));
        return sb.toString();
    }

    private void writeEdges(StringBuilder sb, String title, List<Object> entries) {
        sb.append(title).append(" (").append(entries.size()).append(")\n");
        if (entries.isEmpty()) {
            sb.append("  nothing\n");
            return;
        }
        for (Object item : entries) {
            Map<String, Object> entry = Json.asMap(item);
            Map<String, Object> other = Json.asMap(entry.get("node"));
            sb.append("  #").append(num(other.get("id"))).append(' ')
                    .append(str(other.get("qname")))
                    .append("  ").append(str(entry.get("kind")).toLowerCase())
                    .append(" ×").append(num(entry.get("weight")));
            String breakdown = str(entry.get("breakdown"));
            if (!breakdown.isEmpty()) sb.append("  [").append(breakdown).append(']');
            sb.append('\n');
        }
    }

    private String renderFind(String query) throws Exception {
        Map<String, Object> data = get("/api/search?q=" + enc(query));
        List<Object> results = listArg(data, "results");
        if (results.isEmpty()) return "Nothing matches '" + query + "'.";
        StringBuilder sb = new StringBuilder(results.size() + " match(es) for '" + query + "':\n");
        for (Object item : results) {
            Map<String, Object> node = Json.asMap(item);
            sb.append("  #").append(num(node.get("id"))).append(' ')
                    .append(levelName(num(node.get("layer")))).append(' ')
                    .append(str(node.get("kind"))).append(' ')
                    .append(str(node.get("qname"))).append('\n');
        }
        return sb.toString();
    }

    private String renderProposal() throws Exception {
        Map<String, Object> data = get("/api/proposal");
        List<Object> changes = listArg(data, "changes");
        if (changes.isEmpty()) return "No proposal is active. The map shows the code as it is.";
        StringBuilder sb = new StringBuilder();
        String title = str(data.get("title"));
        sb.append(title.isEmpty() ? "Untitled proposal" : title)
                .append(" - ").append(changes.size()).append(" change(s)\n");
        for (Object item : changes) {
            Map<String, Object> c = Json.asMap(item);
            String op = str(c.get("op"));
            sb.append("  ").append(op);
            switch (op) {
                case "add" -> sb.append(' ').append(str(c.get("kind"))).append(' ')
                        .append(str(c.get("name"))).append(" inside ")
                        .append(refName(c.get("parent")))
                        .append(" [ref ").append(refName(c.get("target"))).append(']');
                case "connect" -> sb.append(' ').append(refName(c.get("from")))
                        .append(" → ").append(refName(c.get("to")))
                        .append(" as ").append(str(c.get("edgeKind")));
                case "move" -> sb.append(' ').append(refName(c.get("target")))
                        .append(" into ").append(refName(c.get("parent")));
                default -> sb.append(' ').append(refName(c.get("target")));
            }
            String note = str(c.get("note"));
            if (!note.isEmpty()) sb.append("  - ").append(note);
            sb.append('\n');
        }
        long lit = Json.asMap(data.get("nodes")).size();
        sb.append("Everything else on the map is dimmed; ").append(lit)
                .append(" node(s) are lit, counting the containers above them.\n");
        return sb.toString();
    }

    private String refName(Object raw) {
        Map<String, Object> ref = Json.asMap(raw);
        String name = str(ref.get("name"));
        long id = num(ref.get("id"));
        String newRef = str(ref.get("ref"));
        if (id > 0) return name.isEmpty() ? "#" + id : name + " (#" + id + ")";
        if (!newRef.isEmpty()) return (name.isEmpty() ? "new node" : name) + " (" + newRef + ")";
        return "?";
    }

    /** A short "what the proposal looks like now" tail, so each call is self-orienting. */
    private String summary(Map<String, Object> result) {
        long count = num(result.get("changes"));
        return "\n" + count + " change(s) in the proposal so far.";
    }

    private static String levelName(long layer) {
        return switch ((int) layer) {
            case 1 -> "module";
            case 2 -> "package";
            case 3 -> "type";
            case 4 -> "function";
            default -> "entity";
        };
    }

    // ---------------------------------------------------------------- transport

    /**
     * Resolving a reference to an id before calling {@code /api/node}, which is keyed on
     * ids only. Anything the map server can resolve - a name, a qualified name, an id -
     * therefore works everywhere, which is what lets an agent use the names it read.
     */
    private String idOf(String ref) throws Exception {
        if (!ref.isEmpty() && ref.chars().allMatch(Character::isDigit)) return ref;
        Map<String, Object> data = get("/api/resolve?ref=" + enc(ref));
        checkError(data);
        return String.valueOf(num(data.get("id")));
    }

    private Map<String, Object> change(Map<String, Object> body) throws Exception {
        Map<String, Object> result = post("/api/proposal/change", body);
        if (num(result.get("ok")) != 1) {
            throw new ToolError(str(result.getOrDefault("error", "the proposal was rejected")));
        }
        return result;
    }

    private Map<String, Object> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    private Map<String, Object> post(String path, Map<String, Object> body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeBody(body), StandardCharsets.UTF_8)));
    }

    private Map<String, Object> delete(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE());
    }

    private Map<String, Object> send(HttpRequest.Builder builder) throws Exception {
        HttpRequest request = builder.timeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ToolError("no map server at " + baseUrl
                    + " (" + e.getMessage() + ").\nStart one with:  codemap serve"
                    + " <graph.db> --port " + URI.create(baseUrl).getPort());
        }
        Map<String, Object> data = Json.asMap(Json.parse(response.body()));
        if (response.statusCode() >= 400) {
            throw new ToolError("map server said " + response.statusCode() + ": "
                    + str(data.getOrDefault("error", response.body())));
        }
        return data;
    }

    private static void checkError(Map<String, Object> data) throws ToolError {
        String error = str(data.get("error"));
        if (!error.isEmpty()) throw new ToolError(error);
    }

    /** Only strings, numbers and string arrays are ever sent, so this is enough. */
    private static String encodeBody(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            if (e.getValue() instanceof List<?> list) {
                Json.str(sb, e.getKey());
                sb.append(":[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    Json.str(sb, String.valueOf(list.get(i)));
                }
                sb.append(']');
            } else {
                Json.field(sb, e.getKey(), String.valueOf(e.getValue()));
            }
        }
        sb.append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------------- output

    private void respond(Object id, String rawResult) {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":");
        appendId(sb, id);
        sb.append(",\"result\":").append(rawResult).append('}');
        out.println(sb);
    }

    private void error(Object id, int code, String message) {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":");
        appendId(sb, id);
        sb.append(",\"error\":{\"code\":").append(code).append(',');
        Json.field(sb, "message", message);
        sb.append("}}");
        out.println(sb);
    }

    /** Request ids come back verbatim; they are numbers or strings, never anything else. */
    private static void appendId(StringBuilder sb, Object id) {
        if (id instanceof Double d && d == Math.rint(d)) sb.append(d.longValue());
        else if (id instanceof Double d) sb.append(d);
        else if (id == null) sb.append("null");
        else Json.str(sb, String.valueOf(id));
    }

    private static String toolResult(String text, boolean isError) {
        StringBuilder sb = new StringBuilder("{\"content\":[{\"type\":\"text\",");
        Json.field(sb, "text", text);
        sb.append("}]");
        if (isError) sb.append(",\"isError\":true");
        sb.append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String need(Map<String, Object> args, String key) throws ToolError {
        String value = str(args.get(key));
        if (value.isEmpty()) throw new ToolError("'" + key + "' is required");
        return value;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Double d) return (int) d.doubleValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<Object> listArg(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static String str(Object value) {
        if (value == null) return "";
        if (value instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(value).strip();
    }

    private static long num(Object value) {
        if (value instanceof Double d) return d.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static final String INSTRUCTIONS = """
            codemap is a map of this codebase: modules, then packages, then types, \
            then functions, with the resolved dependencies between them.

            Read it top-down with get_tree, then get_children / get_node / \
            get_relationships on whatever the task is about.

            When you have a change in mind, draw it instead of describing it. \
            start_proposal, then propose_add / propose_modify / propose_delete / \
            propose_move / propose_connection. Additions show green, changes yellow, \
            deletions red, and everything untouched fades into the background, so the \
            person reading the map can see which module is affected from the top view and \
            drill down to the exact method. Nothing is written to the database.""";
}
