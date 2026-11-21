description = "Streamlord Analysis: editor-independent checks of Datastar expressions and markup in Kotlin and HTML."

dependencies {
    // The strict JSON parser that reads the catalog; core brings nothing but the Kotlin standard
    // library and coroutines, so a consumer of the analysis gets no library it did not have.
    api(project(":streamlord-core"))

    testImplementation(kotlin("test"))
    // DataSignalsSecondArgumentTest compiles DSL calls, so the Kotlin compiler is its oracle.
    testImplementation(project(":streamlord-html"))
    // KotlinParserFasitTest holds where the analysis reads a statement or a value to end to Kotlin's own parser.
    testImplementation(kotlin("compiler-embeddable"))
}

/*
 * The catalog is the single source of truth and lives once, at the repository root. It is
 * compiled in as the text of a Kotlin string when the module builds, so reading it needs no
 * resource: nothing to name in a native image's configuration, and nothing to go missing from a
 * classpath. A constant holds at most 64 KB, so the text is cut into pieces and joined once.
 */
@CacheableTask
abstract class GenerateCatalogText : DefaultTask() {
    /**
     * The catalogs in catalog/, of which there must be one. A file tree, not the file it holds:
     * the configuration cache keeps a file resolved while the build is configured, and after a
     * catalog is renamed would hand the task the old path, where a tree is listed again each run.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogs: ConfigurableFileCollection

    /** catalog/dom-events.json, the DOM events both editors offer after `data-on:`, compiled in beside the catalog. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val domEvents: RegularFileProperty

    /** catalog/template-syntax.json, the template engines' constructs the checks leave out, compiled in beside the catalog. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templateSyntax: RegularFileProperty

    /** catalog/javascript-words.json, the JavaScript words both editors colour in an expression, compiled in beside the catalog. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javascriptWords: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val file =
            catalogs.files.singleOrNull()
                ?: throw GradleException("catalog/ must hold one datastar-*.json, not ${catalogs.files.map { it.name }}")
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
        fun pieces(json: String): String {
            val cuts =
                generateSequence(0) { at ->
                    if (at >=
                        json.length
                    ) {
                        null
                    } else {
                        minOf(json.length, at + 8192).let {
                            if (it < json.length &&
                                json[it - 1].isHighSurrogate()
                            ) {
                                it - 1
                            } else {
                                it
                            }
                        }
                    }
                }
            return cuts.zipWithNext { from, to -> json.substring(from, to) }.joinToString(",\n") { "            ${literal(it)}" }
        }
        val pieces = pieces(file.readText())
        val events = pieces(domEvents.get().asFile.readText())
        val syntax = pieces(templateSyntax.get().asFile.readText())
        val words = pieces(javascriptWords.get().asFile.readText())
        val text =
            """
            |// Generated from catalog/${file.name}, catalog/dom-events.json, catalog/template-syntax.json and catalog/javascript-words.json by :streamlord-analysis:generateCatalogText. Do not edit.
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
            |
            |    /** catalog/dom-events.json as it stands, for [Catalog.domEvents]. */
            |    val DOM_EVENTS: String =
            |        listOf(
            |$events,
            |        ).joinToString("")
            |
            |    /** catalog/template-syntax.json as it stands, for [TEMPLATE_SYNTAX]. */
            |    val TEMPLATE_SYNTAX: String =
            |        listOf(
            |$syntax,
            |        ).joinToString("")
            |
            |    /** catalog/javascript-words.json as it stands, for the words Tokens.kt colours. */
            |    val JAVASCRIPT_WORDS: String =
            |        listOf(
            |$words,
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
        catalogs.from(
            rootProject.layout.projectDirectory
                .dir("catalog")
                .asFileTree
                .matching { include("datastar-*.json") },
        )
        domEvents.set(rootProject.layout.projectDirectory.file("catalog/dom-events.json"))
        templateSyntax.set(rootProject.layout.projectDirectory.file("catalog/template-syntax.json"))
        javascriptWords.set(rootProject.layout.projectDirectory.file("catalog/javascript-words.json"))
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
    // `-PrecordVerdicts=true` has VerdictDigestTest write test/fixtures/verdicts.txt anew instead of comparing with it.
    providers.gradleProperty("recordVerdicts").orNull?.let { systemProperty("streamlord.record", it) }
}

// KotlinParserFasitTest reads every Kotlin file of the repository, so it runs on its own: a change to any of them runs
// it again, and the module's other tests stay up to date. `-PfasitExtra=<dirs>` reads more beside them, and
// `-PrecordFasit=true` takes out of fasit-known.txt what is gone (`all` writes what is found).
tasks.named<Test>("test") { filter { excludeTestsMatching("*KotlinParserFasitTest") } }

val fasitTest = tasks.register<Test>("fasitTest") {
    description = "Holds the analysis's reading of Kotlin's structure to Kotlin's own parser."
    group = "verification"
    val test = tasks.test.get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath
    filter { includeTestsMatching("*KotlinParserFasitTest") }
    val root = rootProject.layout.projectDirectory
    inputs
        .files(root.asFileTree.matching { include("**/*.kt").exclude("**/build/**", "**/node_modules/**", ".claude/**", "**/.gradle/**", "**/.idea/**", "**/out/**") })
        .withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    providers.gradleProperty("fasitExtra").orNull?.let { systemProperty("streamlord.fasit.extra", it) }
    providers.gradleProperty("recordFasit").orNull?.let { systemProperty("streamlord.fasit.record", it) }
}

tasks.check { dependsOn(fasitTest) }

// How the tests read the catalog, conformance/ and the editor fixtures on the JVM; the JavaScript
// build of the same tests, editors/vscode/analysis-js, brings its own. testCommon runs on both.
kotlin.sourceSets.test {
    kotlin.srcDir(project(":streamlord-core").layout.projectDirectory.dir("src/testJvm/kotlin"))
    kotlin.srcDir(project(":streamlord-core").layout.projectDirectory.dir("src/testCommon/kotlin"))
}
