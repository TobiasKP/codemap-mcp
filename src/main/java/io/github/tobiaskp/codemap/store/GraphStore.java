package io.github.tobiaskp.codemap.store;

import io.github.tobiaskp.codemap.model.CodeGraph;
import io.github.tobiaskp.codemap.model.GEdge;
import io.github.tobiaskp.codemap.model.GNode;
import io.github.tobiaskp.codemap.model.Layer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The SQLite side of the map. Nodes and edges only, as specified: everything the
 * frontend draws is a row in one of these two tables.
 */
public final class GraphStore implements AutoCloseable {

    private final Connection conn;

    public GraphStore(Path dbFile) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=OFF");
        }
    }

    public static GraphStore createFresh(Path dbFile) throws Exception {
        Files.createDirectories(dbFile.toAbsolutePath().getParent());
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal"));
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm"));
        GraphStore store = new GraphStore(dbFile);
        store.createSchema();
        return store;
    }

    public Connection connection() {
        return conn;
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE nodes (
                  id        INTEGER PRIMARY KEY,
                  layer     INTEGER NOT NULL,
                  kind      TEXT    NOT NULL,
                  name      TEXT    NOT NULL,
                  qname     TEXT    NOT NULL,
                  parent_id INTEGER NOT NULL DEFAULT 0,
                  path      TEXT    NOT NULL DEFAULT '',
                  lang      TEXT    NOT NULL DEFAULT '',
                  loc       INTEGER NOT NULL DEFAULT 0,
                  x         REAL    NOT NULL DEFAULT 0,
                  y         REAL    NOT NULL DEFAULT 0,
                  r         REAL    NOT NULL DEFAULT 0,
                  in_deg    INTEGER NOT NULL DEFAULT 0,
                  out_deg   INTEGER NOT NULL DEFAULT 0,
                  children  INTEGER NOT NULL DEFAULT 0,
                  -- one line per contributing file: role<TAB>lines<TAB>path
                  files     TEXT    NOT NULL DEFAULT ''
                )""");
            st.executeUpdate("""
                CREATE TABLE edges (
                  id        INTEGER PRIMARY KEY,
                  layer     INTEGER NOT NULL,
                  src_id    INTEGER NOT NULL,
                  dst_id    INTEGER NOT NULL,
                  kind      TEXT    NOT NULL,
                  weight    INTEGER NOT NULL DEFAULT 1,
                  breakdown TEXT    NOT NULL DEFAULT '',
                  -- the view this edge belongs to: the common parent of its endpoints
                  parent_id INTEGER NOT NULL DEFAULT 0
                )""");
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }

    private void createIndices() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE UNIQUE INDEX idx_nodes_qname ON nodes(layer, qname)");
            st.executeUpdate("CREATE INDEX idx_nodes_parent ON nodes(parent_id)");
            st.executeUpdate("CREATE INDEX idx_nodes_layer ON nodes(layer)");
            st.executeUpdate("CREATE INDEX idx_edges_src ON edges(layer, src_id)");
            st.executeUpdate("CREATE INDEX idx_edges_dst ON edges(layer, dst_id)");
            st.executeUpdate("CREATE INDEX idx_edges_parent ON edges(parent_id)");
        }
    }

    /** Assigns ids, writes both tables, then indexes. Ids are stable for a given graph. */
    public void write(CodeGraph graph) throws SQLException {
        assignIds(graph);

        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO nodes(id,layer,kind,name,qname,parent_id,path,lang,loc,x,y,r,"
                            + "in_deg,out_deg,children,files) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int n = 0;
                for (GNode node : graph.allNodes()) {
                    ps.setLong(1, node.id);
                    ps.setInt(2, node.layer.code);
                    ps.setString(3, node.kind.name());
                    ps.setString(4, node.name);
                    ps.setString(5, node.qname);
                    ps.setLong(6, node.parentId);
                    ps.setString(7, node.path);
                    ps.setString(8, node.lang);
                    ps.setInt(9, node.loc);
                    ps.setDouble(10, node.x);
                    ps.setDouble(11, node.y);
                    ps.setDouble(12, node.r);
                    ps.setInt(13, node.inDeg);
                    ps.setInt(14, node.outDeg);
                    ps.setInt(15, node.children);
                    ps.setString(16, node.filesString());
                    ps.addBatch();
                    if (++n % 5000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO edges(id,layer,src_id,dst_id,kind,weight,breakdown,parent_id)"
                            + " VALUES(?,?,?,?,?,?,?,?)")) {
                int n = 0;
                for (GEdge e : graph.allEdges()) {
                    ps.setLong(1, e.id);
                    ps.setInt(2, e.layer.code);
                    ps.setLong(3, e.srcId);
                    ps.setLong(4, e.dstId);
                    ps.setString(5, e.kind.name());
                    ps.setInt(6, e.weight);
                    ps.setString(7, e.breakdownString());
                    ps.setLong(8, e.parentId);
                    ps.addBatch();
                    if (++n % 5000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }

            writeMeta(graph);
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(auto);
        }
        createIndices();
    }

    private void writeMeta(CodeGraph graph) throws SQLException {
        List<String[]> meta = new ArrayList<>();
        meta.add(new String[]{"project_name", graph.projectName});
        meta.add(new String[]{"project_root", graph.projectRoot});
        meta.add(new String[]{"files_scanned", String.valueOf(graph.filesScanned)});
        meta.add(new String[]{"files_failed", String.valueOf(graph.filesFailed)});
        meta.add(new String[]{"schema_version", "1"});
        for (Layer l : Layer.values()) {
            meta.add(new String[]{"nodes_layer_" + l.code, String.valueOf(graph.nodes(l).size())});
            meta.add(new String[]{"edges_layer_" + l.code, String.valueOf(graph.edges(l).size())});
        }
        StringBuilder langs = new StringBuilder();
        graph.languageHistogram.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    if (langs.length() > 0) langs.append(',');
                    langs.append(e.getKey()).append(':').append(e.getValue());
                });
        meta.add(new String[]{"languages", langs.toString()});

        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)")) {
            for (String[] kv : meta) {
                ps.setString(1, kv[0]);
                ps.setString(2, kv[1] == null ? "" : kv[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Ids are handed out layer by layer, sorted by qname, so a rescan of unchanged
     * source produces the same ids and bookmarked URLs keep working.
     */
    private void assignIds(CodeGraph graph) {
        long next = 1;
        for (Layer l : Layer.values()) {
            List<GNode> sorted = new ArrayList<>(graph.nodes(l).values());
            sorted.sort(Comparator.comparing(n -> n.qname));
            for (GNode n : sorted) n.id = next++;
        }
        for (Layer l : Layer.values()) {
            for (GNode n : graph.nodes(l).values()) {
                // layer 2 is a tree, so a package's parent is either its module or
                // another layer-2 node
                GNode parent = switch (l) {
                    case MODULE -> graph.node(Layer.MODULE, n.parentQname);
                    case PACKAGE -> {
                        GNode inModule = graph.node(Layer.MODULE, n.parentQname);
                        yield inModule != null ? inModule : graph.node(Layer.PACKAGE, n.parentQname);
                    }
                    case TYPE -> graph.node(Layer.PACKAGE, n.parentQname);
                    case MEMBER -> graph.node(Layer.TYPE, n.parentQname);
                };
                n.parentId = parent == null ? 0 : parent.id;
            }
        }
        long edgeId = 1;
        for (Layer l : Layer.values()) {
            List<GEdge> sorted = new ArrayList<>(graph.edges(l).values());
            sorted.sort(Comparator.comparing(GEdge::key));
            for (GEdge e : sorted) {
                e.id = edgeId++;
                e.srcId = graph.node(l, e.srcQname).id;
                e.dstId = graph.node(l, e.dstQname).id;
                e.parentId = idOfAnyLayer(graph, e.parentQname);
            }
        }
    }

    /** An edge's common parent can sit on any layer, so look for it on all of them. */
    private static long idOfAnyLayer(CodeGraph graph, String qname) {
        if (qname == null || qname.isEmpty()) return 0;
        for (Layer l : Layer.values()) {
            GNode node = graph.node(l, qname);
            if (node != null) return node.id;
        }
        return 0;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
