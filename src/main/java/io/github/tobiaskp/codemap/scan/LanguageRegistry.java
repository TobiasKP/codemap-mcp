package io.github.tobiaskp.codemap.scan;

import io.github.tobiaskp.codemap.scan.LangSpec.ContainerStyle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The language table. Adding a language means adding one entry here plus its grammar
 * jar on the classpath; nothing else in the pipeline is language aware.
 *
 * <p>Capture names are the contract between the queries and {@link SourceParser}:
 * <ul>
 *   <li>{@code @container} - the declared package/namespace name</li>
 *   <li>{@code @import} - an import path, either a dotted symbol or a file path</li>
 *   <li>{@code @decl.KIND} - the name of a declared type, paired with {@code @scope}
 *       (the whole declaration) so references inside it can be attributed</li>
 *   <li>{@code @owner} + {@code @ownerscope} - an out-of-line member definition such as
 *       {@code void Renderer::draw()}, attributing the body to an already declared type</li>
 *   <li>{@code @ref.KIND} - a reference to a type; KIND becomes the edge kind</li>
 *   <li>{@code @var.name} + {@code @var.type} - a typed name, so that {@code foo.bar()}
 *       can be turned into a call edge to whatever {@code foo} is</li>
 *   <li>{@code @decl.METHOD} / {@code @decl.CONSTRUCTOR} / {@code @decl.FUNCTION} - a
 *       callable, which becomes a layer-4 node inside its type</li>
 *   <li>{@code @call.name} plus optional {@code @call.recv} - a call site. Grammars need
 *       separate patterns for bare and receiver calls and both fire on the same token;
 *       the parser keys them by position and lets the receiver-bearing one win.</li>
 * </ul>
 * Over-capturing is safe: a captured name that is not a type declared somewhere in the
 * project is dropped during resolution.
 */
public final class LanguageRegistry {

    private final Map<String, LangSpec> byId = new HashMap<>();
    private final Map<String, LangSpec> byExtension = new HashMap<>();

    /**
     * Source extensions we recognise as code but have no grammar for. Files like these
     * still become nodes so the map stays complete, they just contribute no edges.
     */
    public static final Set<String> STRUCTURAL_ONLY_EXTENSIONS = Set.of(
            "lua", "sh", "bash", "zsh", "ps1", "bat", "cmd", "sql", "r", "jl", "m", "mm",
            "pl", "pm", "dart", "hs", "ex", "exs", "erl", "clj", "cljs", "groovy",
            "vb", "vbs", "f", "f90", "f95", "for", "pas", "asm", "s", "nim", "zig",
            "vue", "svelte", "elm", "ml", "mli", "fs", "fsx", "tcl", "awk", "d", "cob"
    );

    public LanguageRegistry() {
        register(java());
        register(cpp());
        register(c());
        register(csharp());
        register(python());
        register(typescript());
        register(javascript());
        register(go());
        register(rust());
        register(kotlin());
        register(scala());
        register(swift());
        register(php());
        register(ruby());
    }

    private void register(LangSpec spec) {
        byId.put(spec.id, spec);
        for (String ext : spec.extensions) byExtension.put(ext, spec);
    }

    public LangSpec forExtension(String ext) {
        return byExtension.get(ext.toLowerCase());
    }

    public LangSpec byId(String id) {
        return byId.get(id);
    }

    /** Language label for a file we can place on the map but not parse. */
    public static String structuralLanguage(String ext) {
        return STRUCTURAL_ONLY_EXTENSIONS.contains(ext.toLowerCase()) ? ext.toLowerCase() : null;
    }

    // -------------------------------------------------------------------- java

    private static LangSpec java() {
        return new LangSpec("java", Set.of("java"), "org.treesitter.TreeSitterJava",
                ContainerStyle.PACKAGE, ".", List.of(
                "(package_declaration [(scoped_identifier) (identifier)] @container)",
                "(import_declaration [(scoped_identifier) (identifier)] @import)",

                "(class_declaration name: (identifier) @decl.CLASS) @scope",
                "(interface_declaration name: (identifier) @decl.INTERFACE) @scope",
                "(enum_declaration name: (identifier) @decl.ENUM) @scope",
                "(record_declaration name: (identifier) @decl.RECORD) @scope",
                "(annotation_type_declaration name: (identifier) @decl.ANNOTATION) @scope",

                "(superclass (_) @ref.EXTENDS)",
                "(super_interfaces (type_list (_) @ref.IMPLEMENTS))",
                "(extends_interfaces (type_list (_) @ref.EXTENDS))",
                "(field_declaration type: (_) @ref.FIELD)",
                "(object_creation_expression type: (_) @ref.NEW)",
                "(method_invocation object: (identifier) @ref.CALLOBJ)",
                "(method_invocation object: (field_access) @ref.TYPE_REF)",
                "(method_reference (_) @ref.TYPE_REF)",

                "(formal_parameter type: (_) @ref.TYPE_REF)",
                "(local_variable_declaration type: (_) @ref.TYPE_REF)",
                "(method_declaration type: (_) @ref.TYPE_REF)",
                "(catch_formal_parameter type: (_) @ref.TYPE_REF)",
                "(type_arguments (_) @ref.TYPE_REF)",
                "(cast_expression type: (_) @ref.TYPE_REF)",
                "(instanceof_expression right: (_) @ref.TYPE_REF)",
                "(annotation name: (_) @ref.TYPE_REF)",
                "(marker_annotation name: (_) @ref.TYPE_REF)",
                "(throws (_) @ref.TYPE_REF)",

                "(field_declaration type: (_) @var.type"
                        + " declarator: (variable_declarator name: (identifier) @var.name))",
                "(local_variable_declaration type: (_) @var.type"
                        + " declarator: (variable_declarator name: (identifier) @var.name))",
                "(formal_parameter type: (_) @var.type name: (identifier) @var.name)",

                "(method_declaration name: (identifier) @decl.METHOD) @scope",
                "(constructor_declaration name: (identifier) @decl.CONSTRUCTOR) @scope",
                "(compact_constructor_declaration name: (identifier) @decl.CONSTRUCTOR) @scope",
                "(method_invocation name: (identifier) @call.name)",
                "(method_invocation object: (identifier) @call.recv name: (identifier) @call.name)",
                "(method_invocation object: (field_access field: (identifier) @call.recv)"
                        + " name: (identifier) @call.name)"
        ));
    }

    // --------------------------------------------------------------------- c++

    private static LangSpec cpp() {
        return new LangSpec("cpp", Set.of("cpp", "cc", "cxx", "hpp", "hh", "hxx", "h", "inl", "ipp", "tpp"),
                "org.treesitter.TreeSitterCpp", ContainerStyle.FOLDER, "::", List.of(
                "(namespace_definition name: (namespace_identifier) @container)",
                "(preproc_include path: (_) @import)",

                "(class_specifier name: (type_identifier) @decl.CLASS) @scope",
                "(struct_specifier name: (type_identifier) @decl.STRUCT) @scope",
                "(union_specifier name: (type_identifier) @decl.STRUCT) @scope",
                "(enum_specifier name: (type_identifier) @decl.ENUM) @scope",
                "(alias_declaration name: (type_identifier) @decl.CLASS) @scope",

                // void Renderer::draw() {...} -- attribute the body to Renderer
                "(function_definition declarator:"
                        + " (function_declarator declarator:"
                        + " (qualified_identifier scope: (namespace_identifier) @owner))) @ownerscope",

                "(base_class_clause (type_identifier) @ref.EXTENDS)",
                "(base_class_clause (qualified_identifier) @ref.EXTENDS)",
                "(field_declaration type: (_) @ref.FIELD)",
                "(new_expression type: (_) @ref.NEW)",
                "(declaration type: (_) @ref.TYPE_REF)",
                "(parameter_declaration type: (_) @ref.TYPE_REF)",
                "(function_definition type: (_) @ref.TYPE_REF)",
                "(template_type name: (type_identifier) @ref.TYPE_REF)",
                "(template_argument_list (type_descriptor (_) @ref.TYPE_REF))",
                "(call_expression function: (qualified_identifier scope: (_) @ref.CALL))",
                "(call_expression function: (field_expression argument: (identifier) @ref.CALLOBJ))",
                "(type_identifier) @ref.TYPE_REF",

                "(declaration type: (_) @var.type declarator: (identifier) @var.name)",
                "(declaration type: (_) @var.type"
                        + " declarator: (init_declarator declarator: (identifier) @var.name))",
                "(field_declaration type: (_) @var.type declarator: (field_identifier) @var.name)",
                "(parameter_declaration type: (_) @var.type declarator: (identifier) @var.name)",

                "(function_definition declarator:"
                        + " (function_declarator declarator: (identifier) @decl.FUNCTION)) @scope",
                "(function_definition declarator:"
                        + " (function_declarator declarator: (field_identifier) @decl.METHOD)) @scope",
                "(function_definition declarator: (function_declarator declarator:"
                        + " (qualified_identifier name: (identifier) @decl.METHOD))) @scope",
                "(field_declaration declarator:"
                        + " (function_declarator declarator: (field_identifier) @decl.METHOD))",
                "(declaration declarator:"
                        + " (function_declarator declarator: (identifier) @decl.FUNCTION))",
                "(call_expression function: (identifier) @call.name)",
                "(call_expression function: (field_expression"
                        + " argument: (identifier) @call.recv field: (field_identifier) @call.name))",
                "(call_expression function: (field_expression"
                        + " field: (field_identifier) @call.name))",
                "(call_expression function: (qualified_identifier"
                        + " scope: (namespace_identifier) @call.recv name: (identifier) @call.name))"
        ));
    }

    private static LangSpec c() {
        return new LangSpec("c", Set.of("c"), "org.treesitter.TreeSitterC",
                ContainerStyle.FOLDER, "_", List.of(
                "(preproc_include path: (_) @import)",
                "(struct_specifier name: (type_identifier) @decl.STRUCT) @scope",
                "(union_specifier name: (type_identifier) @decl.STRUCT) @scope",
                "(enum_specifier name: (type_identifier) @decl.ENUM) @scope",
                "(field_declaration type: (_) @ref.FIELD)",
                "(declaration type: (_) @ref.TYPE_REF)",
                "(parameter_declaration type: (_) @ref.TYPE_REF)",
                "(function_definition type: (_) @ref.TYPE_REF)",
                "(type_identifier) @ref.TYPE_REF",
                "(declaration type: (_) @var.type declarator: (identifier) @var.name)",
                "(parameter_declaration type: (_) @var.type declarator: (identifier) @var.name)",

                "(function_definition declarator:"
                        + " (function_declarator declarator: (identifier) @decl.FUNCTION)) @scope",
                "(call_expression function: (identifier) @call.name)"
        ));
    }

    // ------------------------------------------------------------------- c#

    private static LangSpec csharp() {
        return new LangSpec("csharp", Set.of("cs"), "org.treesitter.TreeSitterCSharp",
                ContainerStyle.PACKAGE, ".", List.of(
                "(namespace_declaration name: (_) @container)",
                "(file_scoped_namespace_declaration name: (_) @container)",
                "(using_directive (_) @import)",

                "(class_declaration name: (identifier) @decl.CLASS) @scope",
                "(interface_declaration name: (identifier) @decl.INTERFACE) @scope",
                "(struct_declaration name: (identifier) @decl.STRUCT) @scope",
                "(enum_declaration name: (identifier) @decl.ENUM) @scope",
                "(record_declaration name: (identifier) @decl.RECORD) @scope",

                "(base_list (_) @ref.EXTENDS)",
                "(field_declaration (variable_declaration type: (_) @ref.FIELD))",
                "(property_declaration type: (_) @ref.FIELD)",
                "(object_creation_expression type: (_) @ref.NEW)",
                "(parameter type: (_) @ref.TYPE_REF)",
                "(method_declaration type: (_) @ref.TYPE_REF)",
                "(variable_declaration type: (_) @ref.TYPE_REF)",
                "(type_argument_list (_) @ref.TYPE_REF)",
                "(cast_expression type: (_) @ref.TYPE_REF)",
                "(attribute name: (_) @ref.TYPE_REF)",
                "(member_access_expression expression: (identifier) @ref.CALLOBJ)",

                "(variable_declaration type: (_) @var.type"
                        + " (variable_declarator (identifier) @var.name))",
                "(parameter type: (_) @var.type name: (identifier) @var.name)",

                "(method_declaration name: (identifier) @decl.METHOD) @scope",
                "(constructor_declaration name: (identifier) @decl.CONSTRUCTOR) @scope",
                "(invocation_expression function: (identifier) @call.name)",
                "(invocation_expression function: (member_access_expression"
                        + " expression: (identifier) @call.recv name: (identifier) @call.name))"
        ));
    }

    // ----------------------------------------------------------------- python

    private static LangSpec python() {
        return new LangSpec("python", Set.of("py", "pyi"), "org.treesitter.TreeSitterPython",
                ContainerStyle.FOLDER, ".", List.of(
                "(import_statement name: (dotted_name) @import)",
                "(import_from_statement module_name: (dotted_name) @import)",
                "(import_from_statement module_name: (relative_import) @import)",

                "(class_definition name: (identifier) @decl.CLASS) @scope",

                "(class_definition superclasses: (argument_list (identifier) @ref.EXTENDS))",
                "(class_definition superclasses: (argument_list (attribute) @ref.EXTENDS))",
                "(call function: (identifier) @ref.NEW)",
                "(call function: (attribute object: (identifier) @ref.CALLOBJ))",
                "(type (identifier) @ref.TYPE_REF)",
                "(type (subscript) @ref.TYPE_REF)",
                "(assignment type: (_) @ref.TYPE_REF)",
                "(parameters (typed_parameter type: (_) @ref.TYPE_REF))",
                "(decorator (identifier) @ref.TYPE_REF)",

                "(assignment left: (identifier) @var.name type: (type) @var.type)",
                "(typed_parameter (identifier) @var.name type: (type) @var.type)",

                "(function_definition name: (identifier) @decl.METHOD) @scope",
                "(call function: (identifier) @call.name)",
                "(call function: (attribute object: (identifier) @call.recv"
                        + " attribute: (identifier) @call.name))"
        ));
    }

    // ---------------------------------------------------------- typescript/js

    private static List<String> tsPatterns() {
        return List.of(
                "(import_statement source: (string) @import)",
                "(export_statement source: (string) @import)",
                "(call_expression function: (import) arguments: (arguments (string) @import))",

                "(class_declaration name: (type_identifier) @decl.CLASS) @scope",
                "(abstract_class_declaration name: (type_identifier) @decl.CLASS) @scope",
                "(interface_declaration name: (type_identifier) @decl.INTERFACE) @scope",
                "(enum_declaration name: (identifier) @decl.ENUM) @scope",
                "(type_alias_declaration name: (type_identifier) @decl.RECORD) @scope",

                "(class_heritage (extends_clause (identifier) @ref.EXTENDS))",
                "(extends_type_clause (type_identifier) @ref.EXTENDS)",
                "(implements_clause (type_identifier) @ref.IMPLEMENTS)",
                "(public_field_definition type: (type_annotation (_) @ref.FIELD))",
                "(new_expression constructor: (identifier) @ref.NEW)",
                "(type_annotation (_) @ref.TYPE_REF)",
                "(type_arguments (_) @ref.TYPE_REF)",
                "(member_expression object: (identifier) @ref.CALLOBJ)",
                "(decorator (call_expression function: (identifier) @ref.TYPE_REF))",

                "(variable_declarator name: (identifier) @var.name"
                        + " type: (type_annotation (_) @var.type))",
                "(required_parameter pattern: (identifier) @var.name"
                        + " type: (type_annotation (_) @var.type))",
                "(public_field_definition name: (property_identifier) @var.name"
                        + " type: (type_annotation (_) @var.type))",

                "(method_definition name: (property_identifier) @decl.METHOD) @scope",
                "(function_declaration name: (identifier) @decl.FUNCTION) @scope",
                "(call_expression function: (identifier) @call.name)",
                "(call_expression function: (member_expression"
                        + " object: (identifier) @call.recv property: (property_identifier) @call.name))",
                "(call_expression function: (member_expression"
                        + " object: (this) property: (property_identifier) @call.name))"
        );
    }

    private static LangSpec typescript() {
        return new LangSpec("typescript", Set.of("ts", "tsx", "mts", "cts"),
                "org.treesitter.TreeSitterTypescript", ContainerStyle.FOLDER, ".", tsPatterns());
    }

    private static LangSpec javascript() {
        return new LangSpec("javascript", Set.of("js", "jsx", "mjs", "cjs"),
                "org.treesitter.TreeSitterJavascript", ContainerStyle.FOLDER, ".", List.of(
                "(import_statement source: (string) @import)",
                "(export_statement source: (string) @import)",
                "(class_declaration name: (identifier) @decl.CLASS) @scope",
                "(class_heritage (identifier) @ref.EXTENDS)",
                "(new_expression constructor: (identifier) @ref.NEW)",
                "(member_expression object: (identifier) @ref.CALLOBJ)",
                "(call_expression function: (identifier) @ref.CALL)",

                "(method_definition name: (property_identifier) @decl.METHOD) @scope",
                "(function_declaration name: (identifier) @decl.FUNCTION) @scope",
                "(call_expression function: (identifier) @call.name)",
                "(call_expression function: (member_expression"
                        + " object: (identifier) @call.recv property: (property_identifier) @call.name))"
        ));
    }

    // --------------------------------------------------------------------- go

    private static LangSpec go() {
        return new LangSpec("go", Set.of("go"), "org.treesitter.TreeSitterGo",
                ContainerStyle.FOLDER, ".", List.of(
                "(import_spec path: (interpreted_string_literal) @import)",

                "(type_declaration (type_spec name: (type_identifier) @decl.STRUCT"
                        + " type: (struct_type))) @scope",
                "(type_declaration (type_spec name: (type_identifier) @decl.INTERFACE"
                        + " type: (interface_type))) @scope",

                "(field_declaration type: (_) @ref.FIELD)",
                "(parameter_declaration type: (_) @ref.TYPE_REF)",
                "(composite_literal type: (_) @ref.NEW)",
                "(qualified_type package: (package_identifier) @ref.CALL)",
                "(type_identifier) @ref.TYPE_REF",
                "(selector_expression operand: (identifier) @ref.CALLOBJ)",

                "(var_spec name: (identifier) @var.name type: (_) @var.type)",
                "(parameter_declaration name: (identifier) @var.name type: (_) @var.type)",

                "(function_declaration name: (identifier) @decl.FUNCTION) @scope",
                "(method_declaration name: (field_identifier) @decl.METHOD) @scope",
                "(call_expression function: (identifier) @call.name)",
                "(call_expression function: (selector_expression"
                        + " operand: (identifier) @call.recv field: (field_identifier) @call.name))"
        ));
    }

    // ------------------------------------------------------------------- rust

    private static LangSpec rust() {
        return new LangSpec("rust", Set.of("rs"), "org.treesitter.TreeSitterRust",
                ContainerStyle.FOLDER, "::", List.of(
                "(use_declaration argument: (_) @import)",
                "(struct_item name: (type_identifier) @decl.STRUCT) @scope",
                "(enum_item name: (type_identifier) @decl.ENUM) @scope",
                "(trait_item name: (type_identifier) @decl.TRAIT) @scope",
                "(union_item name: (type_identifier) @decl.STRUCT) @scope",
                "(impl_item type: (type_identifier) @owner) @ownerscope",
                "(field_declaration type: (_) @ref.FIELD)",
                "(parameter type: (_) @ref.TYPE_REF)",
                "(function_item return_type: (_) @ref.TYPE_REF)",
                "(generic_type type: (type_identifier) @ref.TYPE_REF)",
                "(scoped_identifier path: (identifier) @ref.CALL)",
                "(type_identifier) @ref.TYPE_REF",
                "(let_declaration pattern: (identifier) @var.name type: (_) @var.type)",
                "(parameter pattern: (identifier) @var.name type: (_) @var.type)",

                "(function_item name: (identifier) @decl.FUNCTION) @scope",
                "(call_expression function: (identifier) @call.name)",
                "(call_expression function: (field_expression"
                        + " value: (identifier) @call.recv field: (field_identifier) @call.name))"
        ));
    }

    // ----------------------------------------------------------- kotlin/scala

    private static LangSpec kotlin() {
        return new LangSpec("kotlin", Set.of("kt", "kts"), "org.treesitter.TreeSitterKotlin",
                ContainerStyle.PACKAGE, ".", List.of(
                "(package_header (identifier) @container)",
                "(import_header (identifier) @import)",
                "(class_declaration (type_identifier) @decl.CLASS) @scope",
                "(object_declaration (type_identifier) @decl.CLASS) @scope",
                "(delegation_specifier (constructor_invocation (user_type) @ref.EXTENDS))",
                "(delegation_specifier (user_type) @ref.IMPLEMENTS)",
                "(property_declaration (variable_declaration (user_type) @ref.FIELD))",
                "(call_expression (simple_identifier) @ref.CALL)",
                "(user_type (type_identifier) @ref.TYPE_REF)",
                "(navigation_expression (simple_identifier) @ref.CALLOBJ)",

                "(function_declaration (simple_identifier) @decl.METHOD) @scope",
                "(call_expression (simple_identifier) @call.name)"
        ));
    }

    private static LangSpec scala() {
        return new LangSpec("scala", Set.of("scala", "sc"), "org.treesitter.TreeSitterScala",
                ContainerStyle.PACKAGE, ".", List.of(
                "(package_clause name: (_) @container)",
                "(import_declaration (_) @import)",
                "(class_definition name: (identifier) @decl.CLASS) @scope",
                "(trait_definition name: (identifier) @decl.TRAIT) @scope",
                "(object_definition name: (identifier) @decl.CLASS) @scope",
                "(extends_clause (type_identifier) @ref.EXTENDS)",
                "(val_definition type: (_) @ref.FIELD)",
                "(var_definition type: (_) @ref.FIELD)",
                "(parameter type: (_) @ref.TYPE_REF)",
                "(type_identifier) @ref.TYPE_REF",
                "(field_expression value: (identifier) @ref.CALLOBJ)"
        ));
    }

    // ------------------------------------------------------------------ swift

    private static LangSpec swift() {
        return new LangSpec("swift", Set.of("swift"), "org.treesitter.TreeSitterSwift",
                ContainerStyle.FOLDER, ".", List.of(
                "(import_declaration (identifier) @import)",
                "(class_declaration name: (type_identifier) @decl.CLASS) @scope",
                "(protocol_declaration name: (type_identifier) @decl.PROTOCOL) @scope",
                "(inheritance_specifier (user_type (type_identifier) @ref.EXTENDS))",
                "(property_declaration (type_annotation (user_type (type_identifier) @ref.FIELD)))",
                "(parameter type: (user_type (type_identifier) @ref.TYPE_REF))",
                "(type_identifier) @ref.TYPE_REF",
                "(call_expression (simple_identifier) @ref.CALL)"
        ));
    }

    // ------------------------------------------------------------- php / ruby

    private static LangSpec php() {
        return new LangSpec("php", Set.of("php", "phtml"), "org.treesitter.TreeSitterPhp",
                ContainerStyle.PACKAGE, "\\", List.of(
                "(namespace_definition name: (namespace_name) @container)",
                "(namespace_use_clause (qualified_name) @import)",
                "(class_declaration name: (name) @decl.CLASS) @scope",
                "(interface_declaration name: (name) @decl.INTERFACE) @scope",
                "(trait_declaration name: (name) @decl.TRAIT) @scope",
                "(enum_declaration name: (name) @decl.ENUM) @scope",
                "(base_clause (name) @ref.EXTENDS)",
                "(class_interface_clause (name) @ref.IMPLEMENTS)",
                "(object_creation_expression (name) @ref.NEW)",
                "(property_promotion_parameter type: (_) @ref.FIELD)",
                "(property_declaration type: (_) @ref.FIELD)",
                "(simple_parameter type: (_) @ref.TYPE_REF)",
                "(scoped_call_expression scope: (name) @ref.CALL)",
                "(named_type (name) @ref.TYPE_REF)"
        ));
    }

    private static LangSpec ruby() {
        return new LangSpec("ruby", Set.of("rb", "rake"), "org.treesitter.TreeSitterRuby",
                ContainerStyle.FOLDER, "::", List.of(
                "(call method: (identifier) @import (#eq? @import \"require\"))",
                "(class name: (constant) @decl.CLASS) @scope",
                "(module name: (constant) @decl.CLASS) @scope",
                "(superclass (constant) @ref.EXTENDS)",
                "(constant) @ref.TYPE_REF",
                "(call receiver: (constant) @ref.CALL)"
        ));
    }
}
