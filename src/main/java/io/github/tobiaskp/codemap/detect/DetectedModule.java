package io.github.tobiaskp.codemap.detect;

import java.util.LinkedHashSet;
import java.util.Set;

/** A build unit found in the tree: one layer-1 node. */
public final class DetectedModule {

    /** display name, e.g. {@code plugin-hausarzt} or {@code WestRenderer}. */
    public final String name;
    /** project-relative directory that owns this module; "" is the project root. */
    public final String rootRel;
    /** which build file it came from, e.g. {@code maven}, {@code cmake}. */
    public final String buildSystem;

    /**
     * Every string another module's build file might use to refer to this one:
     * artifactId, groupId:artifactId, extra cmake target names, npm name, csproj path.
     */
    public final Set<String> aliases = new LinkedHashSet<>();
    /** raw references pulled out of this module's build file, unresolved. */
    public final Set<String> declaredDeps = new LinkedHashSet<>();

    /** nearest enclosing module, resolved after detection; "" when top level. */
    public String parentModule = "";
    /** true for aggregator poms / add_subdirectory-only CMake files. */
    public boolean aggregator;

    public int fileCount;

    public DetectedModule(String name, String rootRel, String buildSystem) {
        this.name = name;
        this.rootRel = rootRel;
        this.buildSystem = buildSystem;
        this.aliases.add(name);
    }

    /** how deep the module root sits; used to pick the most specific owner of a file. */
    public int depth() {
        return rootRel.isEmpty() ? 0 : rootRel.split("/").length;
    }

    @Override
    public String toString() {
        return name + "@" + (rootRel.isEmpty() ? "." : rootRel);
    }
}
