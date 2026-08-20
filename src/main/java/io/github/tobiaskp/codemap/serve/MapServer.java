package io.github.tobiaskp.codemap.serve;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.tobiaskp.codemap.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * Serves the map: the static page plus a small read-only JSON API over the graph database.
 *
 * <p>Layers 1 and 2 are small enough to ship whole. Layer 3 is fetched per module as the
 * user zooms in, together with stub nodes for edge endpoints that live somewhere else, so
 * a reference leaving a package can be drawn and followed without loading the world.
 */
public final class MapServer {

    /**
     * Every node SELECT uses this list, and {@link #writeNode} reads it by position.
     * Keeping it in one constant is what stops the two from drifting apart when a column
     * is added - the edge columns in {@link #writeNeighbours} start right after it.
     */
    private static final String NODE_COLUMNS =
            "id,layer,kind,name,qname,parent_id,path,lang,loc,x,y,r,in_deg,out_deg,children,files";
    private static final int NODE_COLUMN_COUNT = 16;

    private final Path dbFile;
    private final int port;
    private final Connection conn;
    /** the LLM's proposed change, held in memory only - see {@link ProposalApi}. */
    private final ProposalApi proposals;
    private HttpServer server;

    public MapServer(Path dbFile, int port) throws SQLException {
        this.dbFile = dbFile;
        this.port = port;
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        this.proposals = new ProposalApi(conn);
    }

    public String url() {
        return "http://localhost:" + port + "/";
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handleStatic);
        server.createContext("/api/", this::handleApi);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ---------------------------------------------------------------- routing

    private void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
        try {
            // The proposal endpoints are the only ones that are not GETs: an agent writes
            // the overlay and the viewer reads it, so the method is what separates them.
            String json = switch (path) {
                case "/api/meta" -> meta();
                case "/api/graph" -> graph(q);
                case "/api/node" -> node(q);
                case "/api/children" -> children(q);
                case "/api/tree" -> tree(q);
                case "/api/resolve" -> resolve(q);
                case "/api/search" -> search(q);
                case "/api/proposal" -> method.equals("DELETE")
                        ? proposals.clear()
                        : proposals.read(longOr(q.get("since"), -1));
                case "/api/proposal/start" -> proposals.start(body(exchange));
                case "/api/proposal/change" -> proposals.change(body(exchange));
                default -> null;
            };
            if (json == null) {
                send(exchange, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (SQLException e) {
            StringBuilder sb = new StringBuilder("{");
            Json.field(sb, "error", String.valueOf(e.getMessage()));
            sb.append('}');
            send(exchange, 500, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) {
            send(exchange, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = MapServer.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                send(exchange, 404, "text/plain", ("not found: " + path).getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, contentType(path), in.readAllBytes());
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------- api: meta

    private String meta() throws SQLException {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"meta\":{");
        boolean first = true;
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT key, value FROM meta");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!first) sb.append(',');
                    first = false;
                    Json.field(sb, rs.getString(1), rs.getString(2));
                }
            }
            sb.append("},\"bounds\":");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT MIN(x-r), MIN(y-r), MAX(x+r), MAX(y+r) FROM nodes WHERE layer=1");
                 ResultSet rs = ps.executeQuery()) {
                sb.append('{');
                if (rs.next()) {
                    Json.field(sb, "minX", rs.getDouble(1));
                    sb.append(',');
                    Json.field(sb, "minY", rs.getDouble(2));
                    sb.append(',');
                    Json.field(sb, "maxX", rs.getDouble(3));
                    sb.append(',');
                    Json.field(sb, "maxY", rs.getDouble(4));
                }
                sb.append('}');
            }
        }
        sb.append(",\"db\":");
        Json.str(sb, dbFile.toString());
        sb.append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------ api: graph

    /**
     * A level's worth of graph. Three shapes of request:
     * <ul>
     *   <li>a whole layer (1 and 2 are small enough to ship entire)</li>
     *   <li>{@code layer=3&module=} - every type in one module</li>
     *   <li>{@code layer=N&parent=} - the children of one node, e.g. a class's members</li>
     * </ul>
     * The scoped forms also return the edges that leave the scope, plus stub nodes for
     * their far endpoints, so a reference out of the view can be drawn and followed.
     */
    private String graph(Map<String, String> q) throws SQLException {
        int layer = intOr(q.get("layer"), 1);
        long module = longOr(q.get("module"), 0);
        long parent = longOr(q.get("parent"), 0);
        boolean scoped = module > 0 || parent > 0;

        // the subquery naming the nodes in scope; reused for nodes and for edges
        String scopeSql = module > 0
                ? "SELECT n.id FROM nodes n JOIN nodes p ON n.parent_id = p.id"
                        + " WHERE n.layer = " + layer + " AND p.parent_id = ?"
                : "SELECT id FROM nodes WHERE layer = " + layer + " AND parent_id = ?";
        long scopeId = module > 0 ? module : parent;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"layer\":").append(layer).append(",\"nodes\":[");

        Set<Long> loaded = new LinkedHashSet<>();
        Set<Long> external = new LinkedHashSet<>();
        synchronized (conn) {
            String nodeSql = scoped
                    ? "SELECT " + NODE_COLUMNS + " FROM nodes WHERE id IN (" + scopeSql + ")"
                    : "SELECT " + NODE_COLUMNS + " FROM nodes WHERE layer = ?";
            try (PreparedStatement ps = conn.prepareStatement(nodeSql)) {
                ps.setLong(1, scoped ? scopeId : layer);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) sb.append(',');
                        first = false;
                        writeNode(sb, rs);
                        loaded.add(rs.getLong(1));
                    }
                }
            }

            sb.append("],\"edges\":[");
            String edgeSql = scoped
                    ? "SELECT src_id,dst_id,kind,weight,breakdown,parent_id FROM edges WHERE layer = " + layer
                            + " AND (src_id IN (" + scopeSql + ") OR dst_id IN (" + scopeSql + "))"
                    : "SELECT src_id,dst_id,kind,weight,breakdown,parent_id FROM edges WHERE layer = ?";
            try (PreparedStatement ps = conn.prepareStatement(edgeSql)) {
                if (scoped) {
                    ps.setLong(1, scopeId);
                    ps.setLong(2, scopeId);
                } else {
                    ps.setInt(1, layer);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        long src = rs.getLong(1);
                        long dst = rs.getLong(2);
                        if (scoped) {
                            if (!loaded.contains(src)) external.add(src);
                            if (!loaded.contains(dst)) external.add(dst);
                        }
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"s\":").append(src).append(",\"d\":").append(dst);
                        sb.append(",\"k\":");
                        Json.str(sb, rs.getString(3));
                        sb.append(",\"w\":").append(rs.getInt(4));
                        sb.append(",\"b\":");
                        Json.str(sb, rs.getString(5));
                        sb.append(",\"p\":").append(rs.getLong(6));
                        sb.append('}');
                    }
                }
            }

            // endpoints outside the scope, so a reference leaving it stays followable
            sb.append("],\"stubs\":[");
            if (!external.isEmpty()) {
                String in = placeholders(external.size());
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + NODE_COLUMNS + " FROM nodes WHERE id IN (" + in + ")")) {
                    int i = 1;
                    for (long id : external) ps.setLong(i++, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) sb.append(',');
                            first = false;
                            writeNode(sb, rs);
                        }
                    }
                }
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------- api: node

    private String node(Map<String, String> q) throws SQLException {
        long id = longOr(q.get("id"), 0);
        if (id <= 0) return "{\"error\":\"id required\"}";
        StringBuilder sb = new StringBuilder("{\"node\":");
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + NODE_COLUMNS + " FROM nodes WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return "{\"error\":\"no such node\"}";
                    writeNode(sb, rs);
                }
            }
            sb.append(",\"parents\":[");
            writeAncestors(sb, id);
            sb.append("],\"out\":[");
            writeNeighbours(sb, id, true);
            sb.append("],\"in\":[");
            writeNeighbours(sb, id, false);
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    /** Walks parent_id up to the module, so the UI can show a breadcrumb. */
    private void writeAncestors(StringBuilder sb, long id) throws SQLException {
        List<Long> chain = new ArrayList<>();
        long current = id;
        for (int depth = 0; depth < 8; depth++) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT parent_id FROM nodes WHERE id = ?")) {
                ps.setLong(1, current);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) break;
                    long parent = rs.getLong(1);
                    if (parent == 0) break;
                    chain.add(parent);
                    current = parent;
                }
            }
        }
        boolean first = true;
        for (long ancestor : chain) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + NODE_COLUMNS + " FROM nodes WHERE id = ?")) {
                ps.setLong(1, ancestor);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (!first) sb.append(',');
                        first = false;
                        writeNode(sb, rs);
                    }
                }
            }
        }
    }

    private void writeNeighbours(StringBuilder sb, long id, boolean outgoing) throws SQLException {
        String sql = "SELECT " + prefixed("n") + ",e.kind,e.weight,e.breakdown"
                + " FROM edges e JOIN nodes n ON n.id = e." + (outgoing ? "dst_id" : "src_id")
                + " WHERE e." + (outgoing ? "src_id" : "dst_id") + " = ?"
                + " ORDER BY e.weight DESC LIMIT 400";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"node\":");
                    writeNode(sb, rs);
                    sb.append(",\"kind\":");
                    Json.str(sb, rs.getString(NODE_COLUMN_COUNT + 1));
                    sb.append(",\"weight\":").append(rs.getInt(NODE_COLUMN_COUNT + 2));
                    sb.append(",\"breakdown\":");
                    Json.str(sb, rs.getString(NODE_COLUMN_COUNT + 3));
                    sb.append('}');
                }
            }
        }
    }

    // -------------------------------------------------- api: children and tree

    /**
     * Everything directly inside a node, whatever layer that happens to be. The graph
     * endpoint needs to be told the layer; an agent walking the tree does not know it and
     * should not have to - containment is a tree, so "what is in here" is the question.
     */
    private String children(Map<String, String> q) throws SQLException {
        long id = ref(q.get("ref"));
        if (id < 0) return refError(q.get("ref"));
        StringBuilder sb = new StringBuilder("{\"parent\":").append(id).append(",\"children\":[");
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + NODE_COLUMNS + " FROM nodes WHERE parent_id = ?"
                            + " ORDER BY loc DESC, name LIMIT 500")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) sb.append(',');
                        first = false;
                        writeNode(sb, rs);
                    }
                }
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * The containment tree, a few levels deep. This is the shape an agent needs first: a
     * whole project is too much to read, and the tree read top-down is how you find the
     * part of it a task is actually about.
     *
     * <p>Wide levels are capped rather than streamed in full - a package with 489 entity
     * classes says "489 children, here are the largest 80", which is enough to decide
     * whether to look closer and small enough to read.
     */
    private String tree(Map<String, String> q) throws SQLException {
        String raw = q.get("ref");
        long root = raw == null || raw.isEmpty() ? 0 : ref(raw);
        if (root < 0) return refError(raw);
        int depth = Math.max(1, Math.min(4, intOr(q.get("depth"), 2)));
        int width = Math.max(1, Math.min(200, intOr(q.get("width"), 80)));
        StringBuilder sb = new StringBuilder("{\"root\":").append(root);
        sb.append(",\"depth\":").append(depth).append(",\"children\":");
        writeSubtree(sb, root, depth, width);
        sb.append('}');
        return sb.toString();
    }

    /** One level's rows, read out before recursing: a statement has one cursor. */
    private record TreeRow(long id, int layer, String kind, String name, String qname,
                           int loc, int children, int in, int out) {
    }

    private void writeSubtree(StringBuilder sb, long parent, int depth, int width)
            throws SQLException {
        List<TreeRow> rows = new ArrayList<>();
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id,layer,kind,name,qname,loc,children,in_deg,out_deg FROM nodes"
                            + " WHERE parent_id = ? ORDER BY loc DESC, name LIMIT ?")) {
                ps.setLong(1, parent);
                ps.setInt(2, width);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new TreeRow(rs.getLong(1), rs.getInt(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                                rs.getInt(8), rs.getInt(9)));
                    }
                }
            }
        }
        sb.append('[');
        boolean first = true;
        for (TreeRow row : rows) {
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            Json.field(sb, "id", row.id());
            sb.append(',');
            Json.field(sb, "layer", row.layer());
            sb.append(',');
            Json.field(sb, "kind", row.kind());
            sb.append(',');
            Json.field(sb, "name", row.name());
            sb.append(',');
            Json.field(sb, "qname", row.qname());
            sb.append(',');
            Json.field(sb, "loc", row.loc());
            sb.append(',');
            Json.field(sb, "children", row.children());
            sb.append(',');
            Json.field(sb, "in", row.in());
            sb.append(',');
            Json.field(sb, "out", row.out());
            if (depth > 1 && row.children() > 0) {
                sb.append(",\"inside\":");
                writeSubtree(sb, row.id(), depth - 1, width);
            }
            sb.append('}');
        }
        sb.append(']');
    }

    /**
     * Turns any of the reference forms into an id, so a caller holding a name it read
     * somewhere can reach the id-keyed endpoints without guessing.
     */
    private String resolve(Map<String, String> q) throws SQLException {
        long id = ref(q.get("ref"));
        if (id <= 0) return refError(q.get("ref"));
        StringBuilder sb = new StringBuilder("{");
        Json.field(sb, "id", id);
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, qname, layer, kind FROM nodes WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        sb.append(',');
                        Json.field(sb, "name", rs.getString(1));
                        sb.append(',');
                        Json.field(sb, "qname", rs.getString(2));
                        sb.append(',');
                        Json.field(sb, "layer", rs.getInt(3));
                        sb.append(',');
                        Json.field(sb, "kind", rs.getString(4));
                    }
                }
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** A node id, a qualified name, or a plain unique name; negative when it names nothing. */
    private long ref(String raw) throws SQLException {
        if (raw == null || raw.isEmpty()) return -1;
        try {
            return proposals.resolve(raw).id();
        } catch (ProposalApi.Unresolved e) {
            return -1;
        }
    }

    private String refError(String raw) throws SQLException {
        try {
            proposals.resolve(raw == null ? "" : raw);
        } catch (ProposalApi.Unresolved e) {
            StringBuilder sb = new StringBuilder("{");
            Json.field(sb, "error", e.getMessage());
            sb.append('}');
            return sb.toString();
        }
        return "{\"error\":\"unusable reference\"}";
    }

    // ----------------------------------------------------------- api: search

    private String search(Map<String, String> q) throws SQLException {
        String term = q.getOrDefault("q", "").trim();
        StringBuilder sb = new StringBuilder("{\"results\":[");
        if (term.length() >= 2) {
            synchronized (conn) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + NODE_COLUMNS + " FROM nodes WHERE name LIKE ? OR qname LIKE ?"
                                + " ORDER BY layer, (in_deg + out_deg) DESC LIMIT 60")) {
                    ps.setString(1, "%" + term + "%");
                    ps.setString(2, "%" + term + "%");
                    try (ResultSet rs = ps.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) sb.append(',');
                            first = false;
                            writeNode(sb, rs);
                        }
                    }
                }
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    /** Column order must match every node SELECT above. */
    private static void writeNode(StringBuilder sb, ResultSet rs) throws SQLException {
        sb.append('{');
        Json.field(sb, "id", rs.getLong(1));
        sb.append(',');
        Json.field(sb, "layer", rs.getInt(2));
        sb.append(',');
        Json.field(sb, "kind", rs.getString(3));
        sb.append(',');
        Json.field(sb, "name", rs.getString(4));
        sb.append(',');
        Json.field(sb, "qname", rs.getString(5));
        sb.append(',');
        Json.field(sb, "parent", rs.getLong(6));
        sb.append(',');
        Json.field(sb, "path", rs.getString(7));
        sb.append(',');
        Json.field(sb, "lang", rs.getString(8));
        sb.append(',');
        Json.field(sb, "loc", rs.getInt(9));
        sb.append(',');
        Json.field(sb, "x", rs.getDouble(10));
        sb.append(',');
        Json.field(sb, "y", rs.getDouble(11));
        sb.append(',');
        Json.field(sb, "r", rs.getDouble(12));
        sb.append(',');
        Json.field(sb, "in", rs.getInt(13));
        sb.append(',');
        Json.field(sb, "out", rs.getInt(14));
        sb.append(',');
        Json.field(sb, "children", rs.getInt(15));
        sb.append(',');
        Json.str(sb, "files");
        sb.append(':');
        writeFiles(sb, rs.getString(16));
        sb.append('}');
    }

    /** {@code role<TAB>lines<TAB>path} lines become a JSON array of objects. */
    private static void writeFiles(StringBuilder sb, String packed) {
        sb.append('[');
        if (packed != null && !packed.isEmpty()) {
            boolean first = true;
            for (String line : packed.split("\n")) {
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('{');
                Json.field(sb, "role", parts[0]);
                sb.append(',');
                Json.field(sb, "lines", parseIntOr(parts[1]));
                sb.append(',');
                Json.field(sb, "path", parts[2]);
                sb.append('}');
            }
        }
        sb.append(']');
    }

    private static int parseIntOr(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** the node column list qualified with a table alias. */
    private static String prefixed(String alias) {
        StringBuilder sb = new StringBuilder();
        for (String col : NODE_COLUMNS.split(",")) {
            if (sb.length() > 0) sb.append(',');
            sb.append(alias).append('.').append(col);
        }
        return sb.toString();
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static int intOr(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longOr(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A POST body as a JSON object; an unreadable or non-object body reads as empty. */
    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        byte[] raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = in.readAllBytes();
        }
        if (raw.length == 0) return Map.of();
        return Json.asMap(Json.parse(new String(raw, StandardCharsets.UTF_8)));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    /** Gzips when the client asked for it; layer payloads compress by roughly 5x. */
    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        byte[] payload = body;
        if (accept != null && accept.contains("gzip") && body.length > 1024) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
                gz.write(body);
            }
            payload = buffer.toByteArray();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
