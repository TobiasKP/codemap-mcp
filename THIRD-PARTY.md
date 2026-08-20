# Third-party dependencies

codemap itself is MIT (see [LICENSE](LICENSE)). It has two runtime dependencies, and the
release jar bundles both — so if you redistribute that jar, these terms travel with it.

The repository does **not** vendor any of these binaries. `mvn package` resolves them from
Maven Central, and `tools/fetch-deps.sh` downloads them for the no-Maven build path.

## SQLite JDBC driver

| | |
|---|---|
| Artifact | `org.xerial:sqlite-jdbc:3.53.2.1` |
| Upstream | <https://github.com/xerial/sqlite-jdbc> |
| Licence | Apache License 2.0, plus a 3-clause BSD notice for the original Zentus driver code it derives from — both shipped inside the artifact under `META-INF/maven/org.xerial/sqlite-jdbc/` |

The artifact embeds SQLite itself, which is in the public domain.

## tree-sitter and its grammars

Java bindings and native grammar libraries, from the `tree-sitter-ng` project:

| | |
|---|---|
| Bindings | `io.github.bonede:tree-sitter:0.26.6` |
| Upstream | <https://github.com/bonede/tree-sitter-ng> |

Grammars, one artifact each, all under the `io.github.bonede` group:

`tree-sitter-java:0.23.5`, `tree-sitter-cpp:0.23.4`, `tree-sitter-c:0.24.1`,
`tree-sitter-c-sharp:0.23.1`, `tree-sitter-python:0.25.0`,
`tree-sitter-javascript:0.25.0`, `tree-sitter-typescript:0.23.2`,
`tree-sitter-go:0.25.0`, `tree-sitter-rust:0.24.0`, `tree-sitter-kotlin:0.3.8.1`,
`tree-sitter-php:0.24.2`, `tree-sitter-ruby:0.23.1`, `tree-sitter-scala:0.24.0`,
`tree-sitter-swift:0.5.0`.

**On licence terms for these:** the published jars carry no embedded licence file — I
checked, and they contain only the compiled binding class and the native libraries. So the
authoritative statement of terms is each upstream repository (`tree-sitter/tree-sitter` for
the core, and the per-language `tree-sitter/tree-sitter-<lang>` repositories for the
grammars, which the bindings wrap), rather than anything I could read out of the artifact.
Rather than assert a licence name I cannot verify from the artifact itself, this file points
at the source. If you are redistributing a build of codemap in a context where that matters,
check the upstream repositories for the grammar versions listed above.

Each grammar jar bundles native libraries for linux, macOS and Windows on x86-64 and
arm64, which is why the release jar is ~27 MB and why it needs nothing but a JVM.
