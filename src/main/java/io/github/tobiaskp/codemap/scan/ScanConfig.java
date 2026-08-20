package io.github.tobiaskp.codemap.scan;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Everything the scan needs to know, assembled from the command line. */
public final class ScanConfig {

    /** directory names skipped everywhere: build output, vendored deps, IDE state. */
    public static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of(
            ".git", ".hg", ".svn", ".idea", ".vscode", ".vs", ".settings",
            "node_modules", "bower_components", "vendor", "third_party", "thirdparty",
            "target", "build", "out", "dist", "bin", "obj", "_build",
            ".gradle", ".mvn", ".m2", ".tox", ".nox", ".venv", "venv", "env",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            "cmake-build-debug", "cmake-build-release", "CMakeFiles",
            "vcpkg", "vcpkg_installed", "conan", "packages",
            "coverage", "htmlcov", "site-packages", ".next", ".nuxt", ".cache",
            "Pods", "DerivedData", ".terraform"
    );

    public Path root;
    public Path dbFile;
    public String projectName;

    public final Set<String> excludedDirs = new LinkedHashSet<>(DEFAULT_EXCLUDED_DIRS);
    /** substring matches against the project-relative path; from --exclude. */
    public final Set<String> excludedPathFragments = new LinkedHashSet<>();

    /** files larger than this are skipped: generated blobs, minified bundles, fixtures. */
    public long maxFileSize = 2_000_000;
    public int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    public boolean serve;
    public int port = 7777;
    public boolean openBrowser;
    /** scan only, do not lay out; useful when you just want counts. */
    public boolean skipLayout;
    public boolean verbose;
}
