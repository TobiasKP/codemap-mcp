package io.github.tobiaskp.codemap.mcp;

import io.github.tobiaskp.codemap.util.Json;

import java.util.List;

/**
 * The tool surface, and the descriptions the model actually reads.
 *
 * <p>These descriptions are load-bearing. A tool whose description only says what it does
 * gets called less than one that says <em>when</em> to call it, so each one below states
 * the situation it belongs in. The proposal tools additionally say what the change will
 * look like on the map, because that is the output the person on the other end will see -
 * the model should know it is drawing, not filing a report.
 *
 * <p>{@code ref} is deliberately forgiving everywhere: a node id, a fully qualified name, a
 * plain class name if it is unique, or a ref handed back by {@code propose_add}. An agent
 * that has just read a tree should be able to use the names it read.
 */
final class Tools {

    private Tools() {
    }

    private record Tool(String name, String description, String schema) {
    }

    private static final String REF =
            "A node: its id (e.g. \\\"4711\\\"), its fully qualified name "
                    + "(e.g. \\\"com.example.billing.Invoice\\\"), a plain name if that is unique, "
                    + "or a ref returned by propose_add (e.g. \\\"n1\\\").";

    private static final List<Tool> TOOLS = List.of(
            new Tool("get_tree",
                    "Start here. The containment tree of the project - modules, then what is "
                            + "inside them - with line counts and dependency degrees. Call it "
                            + "first on any question about where something lives or what a "
                            + "change would touch, then narrow down with get_children.",
                    """
                    {"type":"object","properties":{
                      "ref":{"type":"string","description":"Where to start. Omit for the whole project's top level."},
                      "depth":{"type":"integer","description":"Levels to include, 1-4. Default 2."},
                      "width":{"type":"integer","description":"Most children per level, 1-200. Default 60; wide levels are truncated largest-first."}
                    }}"""),

            new Tool("get_node",
                    "Everything known about one node: kind, language, path, line count, how "
                            + "many things reference it, the files it is made of, and the "
                            + "containers above it. Call it before proposing a change to "
                            + "something, to check it is the node you think it is.",
                    "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\","
                            + "\"description\":\"" + REF + "\"}},\"required\":[\"ref\"]}"),

            new Tool("get_children",
                    "What is directly inside a node: the packages of a module, the classes of "
                            + "a package, the functions of a class. Call it to go one level "
                            + "deeper than get_tree returned.",
                    "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\","
                            + "\"description\":\"" + REF + "\"}},\"required\":[\"ref\"]}"),

            new Tool("get_relationships",
                    "The resolved dependencies of a node - what it references and what "
                            + "references it, with the kind of each (call, field, extends, "
                            + "implements, import) and how often. Call it to work out the blast "
                            + "radius of a change before proposing it.",
                    """
                    {"type":"object","properties":{
                      "ref":{"type":"string","description":"REF_DOC"},
                      "direction":{"type":"string","enum":["out","in","both"],"description":"out = what it depends on, in = what depends on it. Default both."}
                    },"required":["ref"]}""".replace("REF_DOC", REF)),

            new Tool("find",
                    "Search nodes by name when you do not know where something lives. Returns "
                            + "ids and qualified names, ranked by how connected they are.",
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\","
                            + "\"description\":\"Part of a name, at least two characters.\"}},"
                            + "\"required\":[\"query\"]}"),

            new Tool("start_proposal",
                    "Begin drawing a change on the map. Call this once, before the first "
                            + "propose_* call, whenever you are planning work on this codebase - "
                            + "instead of describing the plan in prose. It clears any previous "
                            + "proposal. Nothing is ever written to the database.",
                    "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\","
                            + "\"description\":\"What this change is, in a few words.\"}},"
                            + "\"required\":[\"title\"]}"),

            new Tool("propose_add",
                    "Propose something new: a class in a package, a method on a class, a whole "
                            + "package in a module. It appears GREEN inside its parent, and the "
                            + "containers above it turn green too so it is visible from the top "
                            + "view. Returns a ref - use it to add things inside this one or to "
                            + "connect it to existing code.",
                    """
                    {"type":"object","properties":{
                      "parent":{"type":"string","description":"Where it goes. REF_DOC"},
                      "name":{"type":"string","description":"Name of the new thing."},
                      "kind":{"type":"string","description":"CLASS, INTERFACE, METHOD, FUNCTION, PACKAGE, MODULE ... Default CLASS."},
                      "note":{"type":"string","description":"Why it is needed. Shown to the person reading the map."}
                    },"required":["parent","name"]}""".replace("REF_DOC", REF)),

            new Tool("propose_modify",
                    "Propose changing something that already exists. It turns YELLOW, as do "
                            + "the containers above it. Use it for the things you would "
                            + "otherwise describe as \"edit X to do Y\"; the note is where "
                            + "the Y goes.",
                    """
                    {"type":"object","properties":{
                      "target":{"type":"string","description":"REF_DOC"},
                      "note":{"type":"string","description":"What changes about it, and why."}
                    },"required":["target","note"]}""".replace("REF_DOC", REF)),

            new Tool("propose_delete",
                    "Propose removing something. It turns RED. Use it for code the change "
                            + "makes redundant - the map will show what still points at it, "
                            + "which is usually the part a plan gets wrong.",
                    """
                    {"type":"object","properties":{
                      "target":{"type":"string","description":"REF_DOC"},
                      "note":{"type":"string","description":"Why it can go."}
                    },"required":["target"]}""".replace("REF_DOC", REF)),

            new Tool("propose_move",
                    "Propose relocating something to a different parent - a class into another "
                            + "package, a method onto another class. The thing turns yellow and "
                            + "its destination turns green.",
                    """
                    {"type":"object","properties":{
                      "target":{"type":"string","description":"What moves. REF_DOC"},
                      "new_parent":{"type":"string","description":"Where it moves to. REF_DOC"},
                      "note":{"type":"string","description":"Why."}
                    },"required":["target","new_parent"]}""".replace("REF_DOC", REF)),

            new Tool("propose_connection",
                    "Propose a dependency that does not exist yet: A should call B, A should "
                            + "hold a B, A should implement B. Drawn as an arrow tapering "
                            + "towards its target. Either end may be something you added with "
                            + "propose_add. This is how a plan says \"wire it up\" in a way "
                            + "that can be checked against the existing edges.",
                    """
                    {"type":"object","properties":{
                      "from":{"type":"string","description":"The side that will do the calling. REF_DOC"},
                      "to":{"type":"string","description":"The side being used. REF_DOC"},
                      "kind":{"type":"string","description":"CALL, FIELD, EXTENDS, IMPLEMENTS, IMPORT. Default CALL."},
                      "note":{"type":"string","description":"What the connection is for."}
                    },"required":["from","to"]}""".replace("REF_DOC", REF)),

            new Tool("annotate",
                    "Attach a note to a node without claiming it changes. Use it for findings "
                            + "along the way - \"this is the only caller\", \"this looks like "
                            + "the real bug\" - so the reasoning ends up on the map next to the "
                            + "code it is about.",
                    """
                    {"type":"object","properties":{
                      "target":{"type":"string","description":"REF_DOC"},
                      "note":{"type":"string","description":"The note."}
                    },"required":["target","note"]}""".replace("REF_DOC", REF)),

            new Tool("highlight",
                    "Light up several nodes at once without proposing anything about them. Use "
                            + "it to show a set: every caller of a function, every class that "
                            + "would need touching, the path a request takes.",
                    """
                    {"type":"object","properties":{
                      "targets":{"type":"array","items":{"type":"string"},"description":"Nodes to light up. REF_DOC"},
                      "note":{"type":"string","description":"What these have in common."}
                    },"required":["targets"]}""".replace("REF_DOC", REF)),

            new Tool("get_proposal",
                    "Read back the proposal as it currently stands. Call it before finishing, "
                            + "to check the drawing says what you meant - and to summarise it "
                            + "for the person you are answering.",
                    "{\"type\":\"object\",\"properties\":{}}"),

            new Tool("clear_proposal",
                    "Remove the overlay, so the map shows the code as it is again. Call it when "
                            + "a plan is abandoned or once the work has actually been done.",
                    "{\"type\":\"object\",\"properties\":{}}")
    );

    /** The {@code tools/list} result. Schemas are raw JSON, so they are spliced in as-is. */
    static String listJson() {
        StringBuilder sb = new StringBuilder("{\"tools\":[");
        boolean first = true;
        for (Tool tool : TOOLS) {
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            Json.field(sb, "name", tool.name());
            sb.append(',');
            Json.field(sb, "description", tool.description());
            sb.append(",\"inputSchema\":").append(collapse(tool.schema()));
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** The schemas are written multi-line for readability; the wire wants one line. */
    private static String collapse(String schema) {
        StringBuilder sb = new StringBuilder(schema.length());
        boolean inString = false;
        for (int i = 0; i < schema.length(); i++) {
            char c = schema.charAt(i);
            if (c == '"' && (i == 0 || schema.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString && (c == '\n' || c == '\r' || c == ' ' || c == '\t')) continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
