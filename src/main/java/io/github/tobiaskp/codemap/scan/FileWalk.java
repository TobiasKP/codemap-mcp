package io.github.tobiaskp.codemap.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * One walk of the project tree. Both module detection and source scanning read from
 * the result, so a million-file checkout is only touched once.
 */
public final class FileWalk {

    /** project-relative paths of every file that survived the ignore rules. */
    public final List<Path> files = new ArrayList<>();
    /** files skipped for being too big or unreadable. */
    public int skipped;

    private final ScanConfig cfg;

    private FileWalk(ScanConfig cfg) {
        this.cfg = cfg;
    }

    public static FileWalk of(ScanConfig cfg) throws IOException {
        FileWalk walk = new FileWalk(cfg);
        walk.run();
        return walk;
    }

    private void run() throws IOException {
        Files.walkFileTree(cfg.root, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(cfg.root)) return FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString();
                if (cfg.excludedDirs.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                // hidden dirs other than the ones we explicitly allow carry no source
                if (name.startsWith(".") && !name.equals(".github")) return FileVisitResult.SKIP_SUBTREE;
                if (isExcludedPath(cfg.root.relativize(dir).toString())) return FileVisitResult.SKIP_SUBTREE;
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                Path rel = cfg.root.relativize(file);
                if (isExcludedPath(rel.toString())) return FileVisitResult.CONTINUE;
                if (attrs.size() > cfg.maxFileSize) {
                    skipped++;
                    return FileVisitResult.CONTINUE;
                }
                files.add(rel);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                skipped++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isExcludedPath(String relPath) {
        if (cfg.excludedPathFragments.isEmpty()) return false;
        String norm = relPath.replace('\\', '/');
        for (String frag : cfg.excludedPathFragments) {
            if (norm.contains(frag)) return true;
        }
        return false;
    }
}
