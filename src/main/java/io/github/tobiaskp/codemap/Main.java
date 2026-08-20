package io.github.tobiaskp.codemap;

import io.github.tobiaskp.codemap.detect.DetectedModule;
import io.github.tobiaskp.codemap.detect.ModuleDetector;
import io.github.tobiaskp.codemap.graph.GraphBuilder;
import io.github.tobiaskp.codemap.layout.MapLayout;
import io.github.tobiaskp.codemap.mcp.McpServer;
import io.github.tobiaskp.codemap.model.CodeGraph;
import io.github.tobiaskp.codemap.model.Layer;
import io.github.tobiaskp.codemap.scan.FileWalk;
import io.github.tobiaskp.codemap.scan.ProjectScanner;
import io.github.tobiaskp.codemap.scan.ScanConfig;
import io.github.tobiaskp.codemap.serve.MapServer;
import io.github.tobiaskp.codemap.store.GraphStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Entry point. {@code codemap <project-path>} scans any project in any language and
 * writes the three-layer code graph to SQLite; {@code --serve} then opens the map.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            usage();
            return;
        }
        if (args[0].equals("serve")) {
            serveOnly(args);
            return;
        }
        if (args[0].equals("mcp")) {
            mcpOnly(args);
            return;
        }

        ScanConfig cfg = parseArgs(args);
        if (cfg == null) return;

        long started = System.nanoTime();
        System.out.println("codemap scanning " + cfg.root);
        ensureWritableTempDir(cfg.dbFile);

        System.out.println("- walking tree");
        FileWalk walk = FileWalk.of(cfg);
        System.out.println("  " + walk.files.size() + " files considered, " + walk.skipped + " skipped");

        System.out.println("- detecting modules");
        ModuleDetector detector = new ModuleDetector(cfg, walk);
        detector.detect();
        System.out.println("  " + detector.modules().size() + " modules ("
                + buildSystems(detector) + ")");

        System.out.println("- parsing sources");
        ProjectScanner scanner = new ProjectScanner(cfg);
        ProjectScanner.Result scan = scanner.scan(walk, System.out::println);
        System.out.println("  " + scan.parsed + " parsed, " + scan.failed + " failed, "
                + scan.generated + " generated/minified, " + scan.ignored + " not source");
        System.out.println("  languages: " + histogram(scan.languageHistogram));
        if (cfg.verbose) reportRejectedPatterns(scan, scanner);

        System.out.println("- building graph");
        GraphBuilder builder = new GraphBuilder(cfg, detector, scanner.registry());
        CodeGraph graph = builder.build(scan);
        System.out.printf("  refs: %d resolved, %d unknown, %d ambiguous%n",
                builder.resolvedRefs, builder.unresolvedRefs, builder.ambiguousRefs);
        if (builder.groupsCreated > 0) {
            System.out.println("  grouped packages by name path: " + builder.groupsCreated
                    + " grouping levels added");
        }

        if (!cfg.skipLayout) {
            System.out.println("- laying out map");
            MapLayout.run(graph);
        }

        System.out.println("- writing " + cfg.dbFile);
        try (GraphStore store = GraphStore.createFresh(cfg.dbFile)) {
            store.write(graph);
        }

        for (Layer layer : Layer.values()) {
            System.out.printf("  layer %d %-8s %6d nodes  %6d edges%n",
                    layer.code, layer.name().toLowerCase(),
                    graph.nodes(layer).size(), graph.edges(layer).size());
        }
        System.out.printf("done in %.1fs%n", (System.nanoTime() - started) / 1e9);

        if (cfg.serve) {
            MapServer server = new MapServer(cfg.dbFile, cfg.port);
            server.start();
            System.out.println();
            System.out.println("map ready at " + server.url());
            System.out.println("press ctrl-c to stop");
            Thread.currentThread().join();
        }
    }

    private static void serveOnly(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: codemap serve <graph.db> [--port N]");
            return;
        }
        Path db = Path.of(args[1]);
        if (!Files.exists(db)) {
            System.err.println("no such database: " + db);
            return;
        }
        int port = 7777;
        for (int i = 2; i < args.length - 1; i++) {
            if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
        }
        MapServer server = new MapServer(db, port);
        server.start();
        System.out.println("map ready at " + server.url());
        Thread.currentThread().join();
    }

    /**
     * The MCP side speaks JSON-RPC on stdin/stdout, so this process must print nothing
     * else there - it forwards to a running {@code serve} instance, which owns the graph
     * and the proposal overlay. Two processes rather than one because the browser and the
     * agent need to be looking at the same proposal, and an MCP client restarts often.
     */
    private static void mcpOnly(String[] args) throws Exception {
        String url = "http://127.0.0.1:7777";
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--url") && i + 1 < args.length) url = args[++i];
            else if (args[i].equals("--port") && i + 1 < args.length) {
                url = "http://127.0.0.1:" + args[++i];
            }
        }
        new McpServer(url).serve();
    }

    private static ScanConfig parseArgs(String[] args) {
        ScanConfig cfg = new ScanConfig();
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return null;
        }
        cfg.root = root;
        Path fileName = root.getFileName();
        cfg.projectName = fileName == null ? "project" : fileName.toString();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--db" -> cfg.dbFile = Path.of(args[++i]).toAbsolutePath();
                case "--name" -> cfg.projectName = args[++i];
                case "--port" -> cfg.port = Integer.parseInt(args[++i]);
                case "--threads" -> cfg.threads = Math.max(1, Integer.parseInt(args[++i]));
                case "--max-file-size" -> cfg.maxFileSize = Long.parseLong(args[++i]);
                case "--exclude" -> cfg.excludedPathFragments.add(args[++i].replace('\\', '/'));
                case "--include-dir" -> cfg.excludedDirs.remove(args[++i]);
                case "--serve" -> cfg.serve = true;
                case "--no-layout" -> cfg.skipLayout = true;
                case "--verbose" -> cfg.verbose = true;
                default -> {
                    System.err.println("unknown option: " + arg);
                    usage();
                    return null;
                }
            }
        }
        if (cfg.dbFile == null) {
            cfg.dbFile = Path.of("graphs", cfg.projectName + ".db").toAbsolutePath();
        }
        return cfg;
    }

    /**
     * tree-sitter unpacks its JNI natives via {@code java.io.tmpdir}; on locked-down boxes
     * that is not writable, so point it somewhere we know we can write.
     */
    private static void ensureWritableTempDir(Path dbFile) throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
        if (Files.isDirectory(tmp) && Files.isWritable(tmp)) return;
        Path parent = dbFile.toAbsolutePath().getParent();
        Path alt = (parent == null ? Path.of(".") : parent).resolve(".gs-tmp");
        Files.createDirectories(alt);
        System.setProperty("java.io.tmpdir", alt.toString());
        System.out.println("  (temp dir redirected to " + alt + ")");
    }

    /**
     * Query patterns a grammar refused are dropped so one stale pattern cannot take a
     * whole language down - but silently dropping them hides real gaps, so say which.
     */
    private static void reportRejectedPatterns(ProjectScanner.Result scan, ProjectScanner scanner) {
        for (String lang : scan.languageHistogram.keySet()) {
            var spec = scanner.registry().byId(lang);
            if (spec == null) continue;
            var rejected = io.github.tobiaskp.codemap.scan.SourceParser.rejectedPatterns(lang);
            if (rejected.isEmpty()) continue;
            System.out.println("  ! " + lang + ": grammar rejected " + rejected.size()
                    + " of " + spec.patterns.size() + " query patterns");
            for (String pattern : rejected) System.out.println("      " + pattern);
        }
    }

    private static String buildSystems(ModuleDetector detector) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (DetectedModule m : detector.modules()) {
            counts.merge(m.buildSystem, 1, Integer::sum);
        }
        return histogram(counts);
    }

    private static String histogram(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.getKey()).append(' ').append(e.getValue());
                });
        return sb.toString();
    }

    private static void usage() {
        System.out.println("""
                codemap - a map of any codebase

                usage:
                  codemap <project-path> [options]
                  codemap serve <graph.db> [--port N]
                  codemap mcp [--url http://127.0.0.1:7777]

                mcp serves the map to an LLM over stdio: read tools for the graph, and
                propose_* tools that draw a planned change onto the map as a coloured
                overlay. It talks to a running `serve`, and never writes to the database.

                options:
                  --db <file>          where to write the graph (default graphs/<name>.db)
                  --name <name>        project name shown on the map
                  --serve              serve the map once the scan finishes
                  --port <n>           http port for --serve (default 7777)
                  --threads <n>        parser threads (default: cores - 1)
                  --exclude <frag>     skip paths containing this fragment (repeatable)
                  --include-dir <name> stop ignoring a directory, e.g. --include-dir build
                  --max-file-size <n>  skip files larger than n bytes (default 2000000)
                  --no-layout          skip the layout pass
                  --verbose            report parse failures and rejected query patterns
                """);
    }
}
