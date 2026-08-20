package io.github.tobiaskp.codemap.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Parses every source file in the walk, in parallel, into {@link FileFacts}. */
public final class ProjectScanner {

    public static final class Result {
        public final List<FileFacts> facts = new ArrayList<>();
        public final Map<String, Integer> languageHistogram = new HashMap<>();
        public int parsed, failed, ignored, generated;
    }

    private final ScanConfig cfg;
    private final LanguageRegistry registry = new LanguageRegistry();
    /** tree-sitter state is per thread; one parser per thread and language. */
    private final ThreadLocal<Map<String, SourceParser>> parsers = ThreadLocal.withInitial(HashMap::new);
    private final Map<String, Boolean> grammarAvailable = new ConcurrentHashMap<>();

    public ProjectScanner(ScanConfig cfg) {
        this.cfg = cfg;
    }

    public LanguageRegistry registry() {
        return registry;
    }

    /**
     * Parser threads get an explicit 16 MB stack.
     *
     * <p>tree-sitter walks deeply nested trees recursively, and one minified bundle or one
     * very long chained expression is enough to overflow the JVM default. Asking for the
     * stack here rather than relying on {@code -Xss16m} on the command line is what makes
     * a plain {@code java -jar codemap.jar} sufficient - a distributed jar cannot assume
     * anyone passes the right flags.
     */
    private static java.util.concurrent.ThreadFactory parserThreads() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(null, runnable, "parse-" + n.incrementAndGet(), 16L << 20);
            t.setDaemon(true);
            return t;
        };
    }

    public Result scan(FileWalk walk, Consumer<String> progress) throws InterruptedException {
        Result result = new Result();
        List<FileFacts> collected = java.util.Collections.synchronizedList(result.facts);
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger ignored = new AtomicInteger();
        AtomicInteger generated = new AtomicInteger();
        Map<String, AtomicInteger> langCounts = new ConcurrentHashMap<>();
        int total = walk.files.size();

        ExecutorService pool = Executors.newFixedThreadPool(cfg.threads, parserThreads());
        try {
            for (Path rel : walk.files) {
                pool.execute(() -> {
                    try {
                        FileFacts facts = parseOne(rel);
                        if (facts == GENERATED) {
                            generated.incrementAndGet();
                        } else if (facts == null) {
                            ignored.incrementAndGet();
                        } else {
                            collected.add(facts);
                            langCounts.computeIfAbsent(facts.lang, k -> new AtomicInteger()).incrementAndGet();
                        }
                    } catch (Exception | LinkageError e) {
                        failed.incrementAndGet();
                        if (cfg.verbose) {
                            System.err.println("  ! " + rel + ": " + e.getClass().getSimpleName()
                                    + " " + e.getMessage());
                        }
                    } finally {
                        int n = done.incrementAndGet();
                        if (n % 2000 == 0) progress.accept("  parsed " + n + " / " + total + " files");
                    }
                });
            }
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(2, TimeUnit.HOURS)) pool.shutdownNow();
        }

        result.parsed = result.facts.size();
        result.failed = failed.get();
        result.ignored = ignored.get();
        result.generated = generated.get();
        langCounts.forEach((k, v) -> result.languageHistogram.put(k, v.get()));
        return result;
    }

    /** Sentinel for a file skipped as generated or minified output. */
    private static final FileFacts GENERATED = new FileFacts("", "");

    /** Returns null when the file is not source we care about, GENERATED when it is noise. */
    private FileFacts parseOne(Path rel) throws IOException {
        String name = rel.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase();

        LangSpec spec = registry.forExtension(ext);
        String relPath = rel.toString().replace('\\', '/');

        if (spec == null) {
            // still worth a node so the map shows the file, just without edges
            String lang = LanguageRegistry.structuralLanguage(ext);
            if (lang == null) return null;
            byte[] bytes = Files.readAllBytes(cfg.root.resolve(rel));
            if (looksGenerated(name, bytes)) return GENERATED;
            FileFacts facts = new FileFacts(relPath, lang);
            facts.loc = countLines(bytes);
            return facts;
        }

        if (!grammarAvailable.computeIfAbsent(spec.id, id -> SourceParser.isAvailable(spec))) {
            return null;
        }

        byte[] bytes = Files.readAllBytes(cfg.root.resolve(rel));
        if (looksGenerated(name, bytes)) return GENERATED;
        SourceParser parser = parsers.get().computeIfAbsent(spec.id, id -> new SourceParser(spec));
        return parser.parse(relPath, bytes);
    }

    /**
     * Minified bundles and code generator output are technically source but tell you
     * nothing about the architecture, and their one-letter class names collide with
     * everything. One enormous line is the reliable giveaway.
     */
    private static boolean looksGenerated(String fileName, byte[] bytes) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css") || lower.contains("-min.")
                || lower.endsWith(".bundle.js") || lower.endsWith(".pb.go")
                || lower.endsWith("_pb2.py") || lower.endsWith(".g.dart")) {
            return true;
        }
        int longest = 0;
        int current = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                if (current > longest) longest = current;
                current = 0;
            } else {
                current++;
            }
        }
        return Math.max(longest, current) > 4000;
    }

    private static int countLines(byte[] utf8) {
        int lines = utf8.length == 0 ? 0 : 1;
        for (byte b : utf8) {
            if (b == '\n') lines++;
        }
        return lines;
    }
}
