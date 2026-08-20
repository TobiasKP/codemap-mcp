package io.github.tobiaskp.codemap.detect;

import io.github.tobiaskp.codemap.scan.FileWalk;
import io.github.tobiaskp.codemap.scan.ScanConfig;
import io.github.tobiaskp.codemap.util.Json;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds layer-1 modules by reading whatever build files the project happens to use,
 * and turns each build file's dependency list into module -> module edges.
 *
 * <p>Nothing here is language specific beyond the build file syntax, and a project
 * with no recognised build file still gets modules: its top-level source directories.
 */
public final class ModuleDetector {

    private final ScanConfig cfg;
    private final FileWalk walk;

    private final List<DetectedModule> modules = new ArrayList<>();
    /** alias -> module name; how declared deps get resolved. */
    private final Map<String, String> aliasIndex = new HashMap<>();
    /** module roots sorted deepest first, for owner lookup. */
    private List<DetectedModule> byDepth = List.of();

    public ModuleDetector(ScanConfig cfg, FileWalk walk) {
        this.cfg = cfg;
        this.walk = walk;
    }

    public List<DetectedModule> modules() {
        return modules;
    }

    public void detect() {
        List<Path> maven = new ArrayList<>();
        List<Path> cmake = new ArrayList<>();
        List<Path> gradle = new ArrayList<>();
        List<Path> npm = new ArrayList<>();
        List<Path> cargo = new ArrayList<>();
        List<Path> csproj = new ArrayList<>();
        List<Path> pyproject = new ArrayList<>();
        List<Path> gomod = new ArrayList<>();

        for (Path rel : walk.files) {
            String file = rel.getFileName().toString();
            switch (file) {
                case "pom.xml" -> maven.add(rel);
                case "CMakeLists.txt" -> cmake.add(rel);
                case "build.gradle", "build.gradle.kts" -> gradle.add(rel);
                case "package.json" -> npm.add(rel);
                case "Cargo.toml" -> cargo.add(rel);
                case "pyproject.toml", "setup.py" -> pyproject.add(rel);
                case "go.mod" -> gomod.add(rel);
                default -> {
                    if (file.endsWith(".csproj") || file.endsWith(".vcxproj")) csproj.add(rel);
                }
            }
        }

        maven.forEach(this::readMaven);
        cmake.forEach(this::readCMake);
        gradle.forEach(this::readGradle);
        csproj.forEach(this::readMsBuild);
        // these only get a say when nothing stronger claimed their directory
        cargo.forEach(this::readCargo);
        gomod.forEach(this::readGoMod);
        npm.forEach(this::readNpm);
        pyproject.forEach(this::readPython);

        if (modules.isEmpty()) fallbackToDirectories();

        indexAliases();
        resolveNesting();
        byDepth = new ArrayList<>(modules);
        byDepth.sort(Comparator.comparingInt(DetectedModule::depth).reversed());
    }

    /** The module that owns a file: the deepest module root that is a prefix of it. */
    public DetectedModule ownerOf(Path relFile) {
        String p = norm(relFile.toString());
        for (DetectedModule m : byDepth) {
            if (m.rootRel.isEmpty()) return m;
            if (p.startsWith(m.rootRel + "/")) return m;
        }
        return byDepth.isEmpty() ? null : byDepth.get(byDepth.size() - 1);
    }

    /** Declared dependencies as resolved module-name pairs; unknown targets dropped. */
    public List<String[]> declaredDependencyPairs() {
        List<String[]> out = new ArrayList<>();
        for (DetectedModule m : modules) {
            for (String dep : m.declaredDeps) {
                String target = aliasIndex.get(dep);
                if (target == null) {
                    int colon = dep.indexOf(':');
                    if (colon >= 0) target = aliasIndex.get(dep.substring(colon + 1));
                }
                if (target != null && !target.equals(m.name)) out.add(new String[]{m.name, target});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ maven

    private void readMaven(Path rel) {
        String text = read(rel);
        if (text == null) return;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(cfg.root.resolve(rel).toFile());
            Element project = doc.getDocumentElement();
            if (project == null) return;

            String artifactId = childText(project, "artifactId");
            if (artifactId == null || artifactId.isBlank()) return;
            String groupId = childText(project, "groupId");
            Element parent = child(project, "parent");
            if (groupId == null && parent != null) groupId = childText(parent, "groupId");

            DetectedModule m = new DetectedModule(artifactId, dirOf(rel), "maven");
            if (groupId != null) m.aliases.add(groupId + ":" + artifactId);
            m.aggregator = "pom".equals(childText(project, "packaging"));

            collectMavenDeps(child(project, "dependencies"), m);
            Element profiles = child(project, "profiles");
            if (profiles != null) {
                for (Element profile : children(profiles, "profile")) {
                    collectMavenDeps(child(profile, "dependencies"), m);
                }
            }
            add(m);
        } catch (Exception ignored) {
            // a pom we cannot parse simply contributes no module
        }
    }

    private void collectMavenDeps(Element dependencies, DetectedModule m) {
        if (dependencies == null) return;
        for (Element dep : children(dependencies, "dependency")) {
            String g = childText(dep, "groupId");
            String a = childText(dep, "artifactId");
            if (a == null) continue;
            m.declaredDeps.add(g == null ? a : g + ":" + a);
        }
    }

    // ------------------------------------------------------------------ cmake

    private static final Pattern CMAKE_PROJECT = Pattern.compile("(?i)\\bproject\\s*\\(\\s*([A-Za-z0-9_.+-]+)");
    private static final Pattern CMAKE_TARGET =
            Pattern.compile("(?i)\\badd_(?:library|executable)\\s*\\(\\s*([A-Za-z0-9_.+${}-]+)");
    private static final Pattern CMAKE_LINK =
            Pattern.compile("(?i)\\btarget_link_libraries\\s*\\(\\s*([A-Za-z0-9_.+${}-]+)([^)]*)\\)");
    private static final Set<String> CMAKE_KEYWORDS =
            Set.of("PUBLIC", "PRIVATE", "INTERFACE", "STATIC", "SHARED", "MODULE", "OBJECT",
                    "ALIAS", "IMPORTED", "GLOBAL", "EXCLUDE_FROM_ALL", "WIN32", "MACOSX_BUNDLE");

    private void readCMake(Path rel) {
        String text = read(rel);
        if (text == null) return;
        text = stripLineComments(text, "#");

        String projectName = firstGroup(CMAKE_PROJECT, text);
        List<String> targets = new ArrayList<>();
        Matcher tm = CMAKE_TARGET.matcher(text);
        while (tm.find()) {
            String t = expandCMakeVar(tm.group(1), projectName);
            if (t != null && !targets.contains(t)) targets.add(t);
        }

        // a CMakeLists that only pulls in subdirectories is an aggregator, not a module
        boolean aggregator = targets.isEmpty();
        String name = aggregator
                ? (projectName != null ? projectName : dirName(rel))
                : targets.get(0);
        if (name == null || name.isBlank()) return;

        DetectedModule m = new DetectedModule(name, dirOf(rel), "cmake");
        m.aggregator = aggregator;
        // every target declared here resolves to this module, so link edges land correctly
        targets.forEach(m.aliases::add);
        if (projectName != null) m.aliases.add(projectName);

        Matcher lm = CMAKE_LINK.matcher(text);
        while (lm.find()) {
            for (String tok : lm.group(2).trim().split("[\\s\\r\\n]+")) {
                String dep = tok.trim();
                if (dep.isEmpty() || CMAKE_KEYWORDS.contains(dep.toUpperCase())) continue;
                if (dep.startsWith("$") || dep.contains("::") || dep.startsWith("\"")) continue;
                m.declaredDeps.add(dep);
            }
        }
        add(m);
    }

    private String expandCMakeVar(String raw, String projectName) {
        if (!raw.contains("${")) return raw;
        if (projectName != null && (raw.equals("${PROJECT_NAME}") || raw.equals("${CMAKE_PROJECT_NAME}"))) {
            return projectName;
        }
        return null;
    }

    // ----------------------------------------------------------------- gradle

    private static final Pattern GRADLE_PROJECT_DEP =
            Pattern.compile("project\\s*\\(?\\s*[\"']:?([A-Za-z0-9_.:-]+)[\"']");

    private void readGradle(Path rel) {
        String text = read(rel);
        if (text == null) return;
        text = stripLineComments(text, "//");
        String dir = dirOf(rel);
        String name = dir.isEmpty() ? rootName() : dir.substring(dir.lastIndexOf('/') + 1);

        DetectedModule m = new DetectedModule(name, dir, "gradle");
        // gradle refers to modules by their colon path, so register that too
        if (!dir.isEmpty()) m.aliases.add(dir.replace('/', ':'));
        m.aggregator = !text.contains("dependencies") && text.contains("subprojects");

        Matcher pm = GRADLE_PROJECT_DEP.matcher(text);
        while (pm.find()) {
            String dep = pm.group(1);
            m.declaredDeps.add(dep);
            m.declaredDeps.add(dep.substring(dep.lastIndexOf(':') + 1));
        }
        add(m);
    }

    // ---------------------------------------------------------------- msbuild

    private static final Pattern PROJECT_REFERENCE =
            Pattern.compile("(?i)<ProjectReference\\s+Include\\s*=\\s*\"([^\"]+)\"");

    private void readMsBuild(Path rel) {
        String text = read(rel);
        if (text == null) return;
        String file = rel.getFileName().toString();
        String name = file.substring(0, file.lastIndexOf('.'));

        DetectedModule m = new DetectedModule(name, dirOf(rel), "msbuild");
        m.aliases.add(norm(rel.toString()));

        Matcher rm = PROJECT_REFERENCE.matcher(text);
        while (rm.find()) {
            String inc = rm.group(1).replace('\\', '/');
            String base = inc.substring(inc.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            m.declaredDeps.add(dot > 0 ? base.substring(0, dot) : base);
        }
        add(m);
    }

    // ------------------------------------------------------- cargo / go / npm

    private static final Pattern TOML_NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TOML_PATH_DEP =
            Pattern.compile("(?m)^\\s*([A-Za-z0-9_-]+)\\s*=\\s*\\{[^}]*path\\s*=\\s*[\"']([^\"']+)[\"']");

    private void readCargo(Path rel) {
        String text = read(rel);
        if (text == null || claimed(dirOf(rel))) return;
        String name = firstGroup(TOML_NAME, text);
        if (name == null) return;
        DetectedModule m = new DetectedModule(name, dirOf(rel), "cargo");
        Matcher dm = TOML_PATH_DEP.matcher(text);
        while (dm.find()) m.declaredDeps.add(dm.group(1));
        add(m);
    }

    private void readGoMod(Path rel) {
        String text = read(rel);
        if (text == null || claimed(dirOf(rel))) return;
        String name = firstGroup(Pattern.compile("(?m)^\\s*module\\s+(\\S+)"), text);
        if (name == null) return;
        String simple = name.substring(name.lastIndexOf('/') + 1);
        DetectedModule m = new DetectedModule(simple, dirOf(rel), "go");
        m.aliases.add(name);
        add(m);
    }

    private void readNpm(Path rel) {
        String text = read(rel);
        if (text == null || claimed(dirOf(rel))) return;
        Map<String, Object> pkg = Json.asMap(Json.parse(text));
        String name = Json.asString(pkg.get("name"));
        if (name == null || name.isBlank()) return;
        DetectedModule m = new DetectedModule(name, dirOf(rel), "npm");
        m.aliases.add(name.substring(name.lastIndexOf('/') + 1));
        for (String key : new String[]{"dependencies", "devDependencies", "peerDependencies"}) {
            Json.asMap(pkg.get(key)).keySet().forEach(m.declaredDeps::add);
        }
        m.aggregator = pkg.containsKey("workspaces");
        add(m);
    }

    private void readPython(Path rel) {
        String text = read(rel);
        if (text == null || claimed(dirOf(rel))) return;
        String name = firstGroup(TOML_NAME, text);
        if (name == null) name = firstGroup(Pattern.compile("name\\s*=\\s*[\"']([^\"']+)[\"']"), text);
        if (name == null) return;
        add(new DetectedModule(name, dirOf(rel), "python"));
    }

    // --------------------------------------------------------------- fallback

    /**
     * No build file anywhere: use the top-level directories that actually hold source
     * as modules, so the map still has a meaningful first layer.
     */
    private void fallbackToDirectories() {
        Set<String> topLevel = new HashSet<>();
        boolean rootHasFiles = false;
        for (Path rel : walk.files) {
            String p = norm(rel.toString());
            int slash = p.indexOf('/');
            if (slash < 0) rootHasFiles = true;
            else topLevel.add(p.substring(0, slash));
        }
        for (String dir : topLevel) add(new DetectedModule(dir, dir, "directory"));
        if (topLevel.isEmpty() || rootHasFiles) add(new DetectedModule(rootName(), "", "directory"));
    }

    // ---------------------------------------------------------------- helpers

    private void add(DetectedModule m) {
        // one module per directory; the first build system to claim it wins and just
        // absorbs the later one's aliases and dependencies
        for (DetectedModule existing : modules) {
            if (existing.rootRel.equals(m.rootRel)) {
                existing.aliases.addAll(m.aliases);
                existing.declaredDeps.addAll(m.declaredDeps);
                return;
            }
        }
        // same name in two different directories: qualify it, module names are node ids
        for (DetectedModule existing : modules) {
            if (existing.name.equals(m.name)) {
                modules.add(rename(m, m.name + " (" + (m.rootRel.isEmpty() ? "." : m.rootRel) + ")"));
                return;
            }
        }
        modules.add(m);
    }

    private DetectedModule rename(DetectedModule src, String newName) {
        DetectedModule m = new DetectedModule(newName, src.rootRel, src.buildSystem);
        m.aliases.addAll(src.aliases);
        m.declaredDeps.addAll(src.declaredDeps);
        m.aggregator = src.aggregator;
        return m;
    }

    private boolean claimed(String dir) {
        for (DetectedModule m : modules) {
            if (m.rootRel.equals(dir)) return true;
        }
        return false;
    }

    private void indexAliases() {
        for (DetectedModule m : modules) {
            for (String alias : m.aliases) aliasIndex.putIfAbsent(alias, m.name);
        }
    }

    private void resolveNesting() {
        List<DetectedModule> sorted = new ArrayList<>(modules);
        sorted.sort(Comparator.comparingInt(DetectedModule::depth).reversed());
        for (DetectedModule m : modules) {
            for (DetectedModule cand : sorted) {
                if (cand == m || cand.depth() >= m.depth()) continue;
                if (cand.rootRel.isEmpty() || m.rootRel.startsWith(cand.rootRel + "/")) {
                    m.parentModule = cand.name;
                    break;
                }
            }
        }
    }

    private String rootName() {
        Path name = cfg.root.getFileName();
        return name == null ? "project" : name.toString();
    }

    private String read(Path rel) {
        try {
            return Files.readString(cfg.root.resolve(rel), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            try {
                return new String(Files.readAllBytes(cfg.root.resolve(rel)), StandardCharsets.ISO_8859_1);
            } catch (IOException e2) {
                return null;
            }
        }
    }

    private static String dirOf(Path rel) {
        Path parent = rel.getParent();
        return parent == null ? "" : norm(parent.toString());
    }

    private static String dirName(Path rel) {
        String dir = dirOf(rel);
        return dir.isEmpty() ? "" : dir.substring(dir.lastIndexOf('/') + 1);
    }

    private static String norm(String p) {
        return p.replace('\\', '/');
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Removes {@code #} or {@code //} comments so regexes do not match commented-out code. */
    private static String stripLineComments(String text, String marker) {
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int idx = line.indexOf(marker);
            sb.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
        }
        return sb.toString();
    }

    private static Element child(Element parent, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element e && localName(e).equals(name)) return e;
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element e && localName(e).equals(name)) out.add(e);
        }
        return out;
    }

    private static String childText(Element parent, String name) {
        Element c = child(parent, name);
        if (c == null) return null;
        String t = c.getTextContent();
        return t == null ? null : t.trim();
    }

    private static String localName(Element e) {
        String tag = e.getTagName();
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }
}
