package io.github.tobiaskp.codemap.model;

/**
 * One node of the map. {@code qname} is the identity: unique per layer, and what
 * edges are resolved against before ids exist.
 */
public final class GNode {

    /**
     * One file contributing to this node. A C++ class is declared in a header and
     * implemented in a .cpp, so a single type routinely spans two files; the roles say
     * which is which, and the line counts add up to {@link #loc}.
     */
    public record FileRef(String path, String role, int lines) {
        public static final String DECLARATION = "declaration";
        public static final String IMPLEMENTATION = "implementation";
        public static final String FILE = "file";
    }

    /** every file that contributed, in the order they were found. */
    public final java.util.List<FileRef> files = new java.util.ArrayList<>();

    public long id;
    public final Layer layer;
    public final NodeKind kind;
    /** short label drawn on the map. */
    public final String name;
    /** fully qualified, unique within the layer. */
    public final String qname;
    /** owning node one layer up; 0 for modules. */
    public long parentId;
    public String parentQname;
    /** project-relative path of the file or directory this came from. */
    public String path = "";
    public String lang = "";
    public int loc;

    /** world coordinates, filled in by the layout pass. */
    public double x, y, r;

    /** counted after the graph is complete; drives glyph size and label priority. */
    public int inDeg, outDeg, children;

    public GNode(Layer layer, NodeKind kind, String name, String qname, String parentQname) {
        this.layer = layer;
        this.kind = kind;
        this.name = name;
        this.qname = qname;
        this.parentQname = parentQname;
    }

    public int degree() {
        return inDeg + outDeg;
    }

    /** Records a contributing file, ignoring a path already present. */
    public void addFile(String path, String role, int lines) {
        for (FileRef ref : files) {
            if (ref.path().equals(path)) return;
        }
        files.add(new FileRef(path, role, lines));
    }

    /** {@code role<TAB>lines<TAB>path} per line; path last so it may contain anything. */
    public String filesString() {
        StringBuilder sb = new StringBuilder();
        for (FileRef ref : files) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(ref.role()).append('\t').append(ref.lines()).append('\t').append(ref.path());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return layer + ":" + qname;
    }
}
