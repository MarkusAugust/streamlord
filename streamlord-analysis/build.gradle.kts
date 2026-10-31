description = "Streamlord Analysis: editor-independent checks of Datastar expressions and markup in Kotlin and HTML."

dependencies {
    // The strict JSON parser that reads the catalog; core brings nothing but the Kotlin standard
    // library and coroutines, so a consumer of the analysis gets no library it did not have.
    api(project(":streamlord-core"))

    testImplementation(kotlin("test"))
    // DataSignalsSecondArgumentTest compiles DSL calls, so the Kotlin compiler is its oracle.
    testImplementation(project(":streamlord-html"))
}

/*
 * The catalog is the single source of truth and lives once, at the repository root. It is
 * compiled in as the text of a Kotlin string when the module builds, so reading it needs no
 * resource: nothing to name in a native image's configuration, and nothing to go missing from a
 * classpath. A constant holds at most 64 KB, so the text is cut into pieces and joined once.
 */
@CacheableTask
abstract class GenerateCatalogText : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = catalog.get().asFile
        val version = file.name.removePrefix("datastar-").removeSuffix(".json")
        val declared = (groovy.json.JsonSlurper().parse(file) as Map<*, *>)["version"]
        check(declared == version) { "${file.name} says it is Datastar $declared" }

        fun literal(piece: String): String =
            buildString {
                append('"')
                for (c in piece) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '$' -> append("\\$")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                    }
                }
                append('"')
            }
        // Cut at most every 8192 characters, never between the two halves of a surrogate pair.
        val json = file.readText()
        val cuts = generateSequence(0) { at -> if (at >= json.length) null else minOf(json.length, at + 8192).let { if (it < json.length && json[it - 1].isHighSurrogate()) it - 1 else it } }
        val pieces = cuts.zipWithNext { from, to -> json.substring(from, to) }.joinToString(",\n") { "            ${literal(it)}" }
        val text =
            """
            |// Generated from catalog/${file.name} by :streamlord-analysis:generateCatalogText. Do not edit.
            |package io.github.markusaugust.streamlord.analysis
            |
            |/** catalog/${file.name} as it stands, for [Catalog.default]. */
            |internal object CatalogText {
            |    const val VERSION: String = "$version"
            |
            |    val JSON: String =
            |        listOf(
            |$pieces,
            |        ).joinToString("")
            |}
            |
            """.trimMargin()
        val out = output.get().asFile.resolve("io/github/markusaugust/streamlord/analysis/CatalogText.kt")
        out.parentFile.mkdirs()
        out.writeText(text)
    }
}

val generateCatalogText =
    tasks.register<GenerateCatalogText>("generateCatalogText") {
        catalog.set(
            rootProject.layout.projectDirectory
                .dir("catalog")
                .asFileTree
                .matching { include("datastar-*.json") }
                .singleFile,
        )
        output.set(layout.buildDirectory.dir("generated/catalogText"))
    }

kotlin.sourceSets.main {
    kotlin.srcDir(generateCatalogText)
}

// The tests read these by path: the cases the VS Code extension shares with them (the corpus, the
// SSE corpus, the second argument of dataSignals, the templates) and the client's fixtures.
// Declared, so a change to a case alone runs the tests again rather than finding them up to date
// or cached.
tasks.withType<Test>().configureEach {
    val root = rootProject.layout.projectDirectory
    inputs.dir(root.dir("editors/vscode/test/fixtures")).withPropertyName("editorFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(root.dir("conformance/client")).withPropertyName("clientFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(root.dir("conformance/dsl")).withPropertyName("dslFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(root.dir("conformance/dom")).withPropertyName("domFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}
