description = "Streamlord core: the Datastar protocol, pure and without allegiance to any framework."

dependencies {
    api(libs.kotlinx.coroutines.core)

    // @Language on the HTML, JSON and JavaScript parameters, for IntelliJ. compileOnly: it never
    // reaches a consumer from here, and kotlin-stdlib ships the same artifact anyway.
    compileOnly(libs.jetbrains.annotations)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

/*
 * A consumer on Spring Boot runs on Boot's pin of coroutines, 1.10.2, which is the compile-time
 * version the default test task covers; a build without Boot's dependency management resolves the
 * highest on its classpath, Ktor's among them. So the tests also run on the newest release.
 */
val coroutinesNewestVersion = libs.versions.coroutinesNewest.get()

val newestCoroutinesTestRuntimeClasspath: Configuration =
    configurations.create("newestCoroutinesTestRuntimeClasspath") {
        extendsFrom(configurations.testRuntimeClasspath.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        }
        resolutionStrategy.eachDependency {
            val coroutines = requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")
            if (coroutines) useVersion(coroutinesNewestVersion)
        }
    }

val testNewestCoroutines =
    tasks.register<Test>("testNewestCoroutines") {
        description = "Runs the tests against kotlinx-coroutines $coroutinesNewestVersion."
        group = "verification"
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().output + sourceSets.main.get().output + newestCoroutinesTestRuntimeClasspath
        useJUnitPlatform()
    }

tasks.check { dependsOn(testNewestCoroutines) }

// The tests read these by path, so a change there must rerun them and not be served from the cache.
// CatalogNamesTest reads the catalog itself, to hold the generated names to it.
tasks.withType<Test>().configureEach {
    val root = rootProject.layout.projectDirectory
    inputs.dir(root.dir("catalog")).withPropertyName("catalog").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(root.dir("conformance/client")).withPropertyName("clientFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(root.dir("conformance/dom")).withPropertyName("domFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(root.file("editors/vscode/test/fixtures/twins/reference-hyphens.json"), root.file("editors/vscode/test/fixtures/twins/request-urls.json"))
        .withPropertyName("editorFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/*
 * The names core reads from the catalog, written out as Kotlin when the module builds: the
 * attributes by what their value is, and the two prefixes. Literal sets, so a server neither
 * parses JSON nor needs a resource for them, and a native image needs no configuration. The
 * analysis reads the whole catalog itself; CatalogNamesTest holds these to the file.
 */
@CacheableTask
abstract class GenerateCatalogNames : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = catalog.get().asFile
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
        val version = json["version"] as String
        check(file.name == "datastar-$version.json") { "${file.name} says it is Datastar $version" }

        @Suppress("UNCHECKED_CAST")
        val attributes = (json["attributes"] as List<Map<String, Any?>>)
        val names = attributes.map { it["name"] as String }
        fun kind(kind: String) = attributes.filter { (it["valueKind"] ?: "none") == kind }.map { it["name"] as String }
        fun literal(value: String): String {
            check(value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) { "unexpected catalog name $value" }
            return "\"$value\""
        }
        fun set(values: List<String>) = values.joinToString(",\n", "\n        setOf(\n", ",\n        )") { "            ${literal(it)}" }

        val text =
            """
            |// Generated from catalog/${file.name} by :streamlord-core:generateCatalogNames. Do not edit.
            |package io.github.markusaugust.streamlord.core.catalog
            |
            |import io.github.markusaugust.streamlord.core.InternalStreamlordApi
            |
            |/** The names in catalog/${file.name} that core reads, in the catalog's order. */
            |@InternalStreamlordApi
            |public object CatalogNames {
            |    /** The Datastar version the catalog describes. */
            |    public const val VERSION: String = ${literal(version)}
            |
            |    /** The prefix of the standard bundle. */
            |    public const val PREFIX: String = ${literal(json["prefix"] as String)}
            |
            |    /** The prefix of the aliased bundle. */
            |    public const val ALIASED_PREFIX: String = ${literal(json["aliasedPrefix"] as String)}
            |
            |    /** Every attribute, free and Pro. */
            |    public val attributes: Set<String> =${set(names)}
            |
            |    /** The attributes whose value is an expression, `valueKind: "expression"`. */
            |    public val expressionAttributes: Set<String> =${set(kind("expression"))}
            |
            |    /** The attributes whose value is a signal filter, `valueKind: "filter"`. */
            |    public val filterAttributes: Set<String> =${set(kind("filter"))}
            |
            |    /** The attributes whose value is a signal path, `valueKind: "signal"`. */
            |    public val signalAttributes: Set<String> =${set(kind("signal"))}
            |}
            |
            """.trimMargin()
        val out = output.get().asFile.resolve("io/github/markusaugust/streamlord/core/catalog/CatalogNames.kt")
        out.parentFile.mkdirs()
        out.writeText(text)
    }
}

val generateCatalogNames =
    tasks.register<GenerateCatalogNames>("generateCatalogNames") {
        // The one catalog there is; a second version beside it is a decision this build should not guess.
        catalog.set(
            rootProject.layout.projectDirectory
                .dir("catalog")
                .asFileTree
                .matching { include("datastar-*.json") }
                .singleFile,
        )
        output.set(layout.buildDirectory.dir("generated/catalogNames"))
    }

kotlin.sourceSets.main {
    kotlin.srcDir(generateCatalogNames)
}
