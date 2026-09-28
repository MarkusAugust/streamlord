plugins {
    alias(libs.plugins.kotlin.serialization)
}

description =
    "Compiles every Kotlin example in the documentation against the real modules. " +
        "Never published; it exists so an example cannot go stale without the build saying so."

dependencies {
    implementation(project(":streamlord-core"))
    implementation(project(":streamlord-html"))
    implementation(project(":streamlord-html-pro"))
    implementation(project(":streamlord-ktor"))
    implementation(project(":streamlord-spring"))
    implementation(project(":streamlord-json-kotlinx"))
    implementation(project(":streamlord-json-jackson"))

    // The adapters keep their frameworks compileOnly. The samples need them for real.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.spring.web)
    implementation(libs.spring.webmvc)
    implementation(libs.jakarta.servlet)
    implementation(libs.kotlinx.html)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jackson.kotlin)
}

/**
 * Pulls every ```kotlin block out of the documentation, wraps it in the context the block
 * says it belongs to, and leaves a compilable file behind. Compiling those files is what
 * catches a renamed function or a changed parameter on a page that still shows the old call.
 *
 * The marker is mandatory. A block without `sample=` fails the build rather than being
 * skipped quietly, because a net with holes you cannot see is worse than no net at all.
 */
abstract class ExtractDocSamples : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pages: DirectoryProperty

    /**
     * Pages outside the documentation folder that carry examples too. The README is the most
     * read code in the project, so it is held to the same rule as any page on the site.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extraPages: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val target: DirectoryProperty

    private data class Problem(val page: String, val line: Int, val message: String)

    private val imports =
        """
        import io.github.markusaugust.streamlord.core.*
        import io.github.markusaugust.streamlord.core.application.*
        // core.protocol is deliberately absent: it declares a DatastarAttributes that
        // collides by name with the one in streamlord-html, and a sample that has to
        // disambiguate an import is teaching the wrong lesson.
        import io.github.markusaugust.streamlord.core.domain.*
        import io.github.markusaugust.streamlord.core.port.driven.*
        import io.github.markusaugust.streamlord.core.port.driving.*
        import io.github.markusaugust.streamlord.html.*
        import io.github.markusaugust.streamlord.ktor.*
        import io.github.markusaugust.streamlord.spring.*
        import io.github.markusaugust.streamlord.json.kotlinx.*
        import io.github.markusaugust.streamlord.json.jackson.*
        import kotlinx.coroutines.flow.*
        import kotlinx.html.*
        import kotlinx.serialization.Serializable
        import kotlin.time.Duration.Companion.milliseconds
        import kotlin.time.Duration.Companion.seconds
        """.trimIndent()

    /**
     * What each `sample=` name wraps the block in. A name is added the day a page needs it,
     * not before; an unknown one fails the build with the list of the names that exist.
     */
    private fun wrap(context: String, body: String, name: String): String? =
        when (context) {
            "ktor-routing" ->
                "import io.ktor.server.application.*\nimport io.ktor.server.routing.*\n$imports\n\n" +
                    "fun io.ktor.server.routing.Route.$name() {\n$body\n}\n"
            "ktor-application" ->
                "import io.ktor.server.application.*\nimport io.ktor.server.routing.*\n" +
                    "import io.ktor.server.response.*\nimport io.ktor.server.plugins.statuspages.*\n$imports\n\n" +
                    "fun io.ktor.server.application.Application.$name() {\n$body\n}\n"
            "spring" ->
                "import org.springframework.web.bind.annotation.*\n" +
                    "import org.springframework.context.annotation.*\n" +
                    "import jakarta.servlet.http.HttpServletRequest\n" +
                    "import jakarta.servlet.http.HttpServletResponse\n$imports\n\n" +
                    "class $name {\n$body\n}\n"
            "html" -> "$imports\n\nfun kotlinx.html.FlowContent.$name() {\n$body\n}\n"
            "statements" -> "$imports\n\nsuspend fun $name() {\n$body\n}\n"
            "declarations" -> "$imports\n\n$body\n"
            else -> null
        }

    private val known = listOf("declarations", "html", "ktor-application", "ktor-routing", "spring", "statements")

    @TaskAction
    fun extract() {
        val out = target.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val problems = mutableListOf<Problem>()
        val transcripts = mutableListOf<Pair<String, String>>()
        var written = 0

        val markdown =
            (pages.get().asFile.walkTopDown().filter { it.extension == "md" } + extraPages.files)
                .sortedBy { it.name }
        for (page in markdown) {
            val lines = page.readLines()
            var index = 0

            while (index < lines.size) {
                if (!lines[index].startsWith("```")) {
                    index++
                    continue
                }

                val info = lines[index].removePrefix("```").trim()
                val closing = lines.drop(index + 1).indexOfFirst { it.startsWith("```") }
                if (closing < 0) break

                val body = lines.subList(index + 1, index + 1 + closing).joinToString("\n")
                val fenceLine = index + 1
                index += closing + 2

                if (info.substringBefore(" ") != "kotlin") continue

                val wire = info.split(" ").firstOrNull { it.startsWith("wire=") }?.removePrefix("wire=")
                if (wire != null) {
                    val fn = "wire_" + wire.replace('-', '_')
                    transcripts += wire to fn
                    out.resolve("$fn.kt").writeText(
                        "$imports\n\nfun $fn(): List<DatastarEvent> = listOf(\n$body\n)\n",
                    )
                    written++
                    continue
                }

                val marker = info.split(" ").firstOrNull { it.startsWith("sample=") }
                if (marker == null) {
                    problems += Problem(page.name, fenceLine, "kotlin block without a sample= or wire= marker. Use one of $known, or sample=none.")
                    continue
                }

                val context = marker.removePrefix("sample=")
                if (context == "none") continue

                val source = wrap(context, body, "sample_${page.nameWithoutExtension.replace('-', '_')}_$fenceLine")
                if (source == null) {
                    problems += Problem(page.name, fenceLine, "unknown context '$context'. Known: $known, and none.")
                    continue
                }

                out.resolve("sample_${page.nameWithoutExtension.replace('-', '_')}_$fenceLine.kt").writeText(source)
                written++
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The documentation has unchecked Kotlin examples:\n" +
                    problems.joinToString("\n") { "  ${it.page}:${it.line}  ${it.message}" },
            )
        }

        /*
         * The dispatcher, written rather than reflected over: the task already knows every
         * transcript's name, and a generated `main` that names them is easier to debug than
         * a scan of the classpath.
         */
        val entries = transcripts.joinToString(",\n") { (name, fn) -> "        \"$name\" to ::$fn" }
        out.resolve("WireTranscripts.kt").writeText(
            """
            import io.github.markusaugust.streamlord.core.domain.DatastarEvent
            import io.github.markusaugust.streamlord.core.protocol.SseEncoder
            import java.io.File

            /** Generated. Writes the exact bytes each documented event puts on the wire. */
            fun main(args: Array<String>) {
                val target = File(args[0])
                target.mkdirs()
                val all: List<Pair<String, () -> List<DatastarEvent>>> = listOf(
            $entries
                )
                for ((name, events) in all) {
                    File(target, "${'$'}name.sse")
                        .writeText(events().joinToString("") { SseEncoder.encode(it) })
                }
                println("Wrote ${'$'}{all.size} wire transcripts.")
            }
            """.trimIndent(),
        )

        logger.lifecycle("Extracted $written documentation samples and ${transcripts.size} transcripts.")
    }
}

val generateDocSamples =
    tasks.register<ExtractDocSamples>("generateDocSamples") {
        description = "Extracts the Kotlin examples from the documentation into compilable sources."
        group = "verification"
        pages.set(rootProject.layout.projectDirectory.dir("docs/src/content/docs"))
        extraPages.from(rootProject.layout.projectDirectory.file("README.md"))
        target.set(layout.buildDirectory.dir("generated/samples"))
    }

kotlin.sourceSets.named("main") { kotlin.srcDir(generateDocSamples.map { it.target }) }

/*
 * A name to run on its own, and a name that reads clearly in a CI log. The root
 * `build` task already reaches it through `assemble`, so CI needs no extra step.
 */
tasks.register("checkDocSamples") {
    description = "Compiles every Kotlin example in the documentation."
    group = "verification"
    dependsOn(tasks.named("compileKotlin"))
}


/*
 * The dependency trees the install page prints are resolved facts, and a fact in
 * markdown rots the moment someone bumps a version in libs.versions.toml. This
 * resolves each module the way a consumer would and fails when the page disagrees.
 *
 * A block opts in by naming its module:
 *
 *     ```deps=streamlord-json-jackson2 sample=none
 *     com.fasterxml.jackson.core:jackson-databind 2.22.3
 *     ```
 *
 * Only coordinates are compared, not the drawing around them, so the tree glyphs
 * stay a presentation choice.
 */
val documentedModules =
    listOf("streamlord-json-kotlinx", "streamlord-json-jackson", "streamlord-json-jackson2")

abstract class CheckDocDependencies : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pages: DirectoryProperty

    /** Module name to the coordinates a consumer of it actually resolves. */
    @get:Input
    abstract val resolved: MapProperty<String, Set<String>>

    /**
     * What the page leaves out on purpose: the Kotlin standard library, which every
     * Kotlin project has, and Streamlord's own modules, which arrive with the adapter.
     */
    private fun documented(coordinate: String): Boolean =
        !coordinate.startsWith("org.jetbrains.kotlin:kotlin-stdlib") &&
            !coordinate.startsWith("org.jetbrains:annotations") &&
            !coordinate.startsWith("org.jetbrains.kotlinx:kotlinx-coroutines") &&
            !coordinate.startsWith("io.github.markusaugust.streamlord:")

    /** `kotlinx-serialization-json` and its `-jvm` variant are one library to a reader. */
    private fun normalise(coordinate: String): String = coordinate.replace("-jvm:", ":")

    @TaskAction
    fun check() {
        val claims = mutableMapOf<String, MutableSet<String>>()
        val coordinate = Regex("""([\w.\-]+):([\w.\-]+)[ :]+([\w.\-]+)""")

        for (page in pages.get().asFile.walkTopDown().filter { it.extension == "md" }) {
            val lines = page.readLines()
            var index = 0
            while (index < lines.size) {
                val info = lines[index].takeIf { it.startsWith("```") }?.removePrefix("```")?.trim()
                if (info == null) {
                    index++
                    continue
                }
                val end = lines.drop(index + 1).indexOfFirst { it.startsWith("```") }
                if (end < 0) break
                val body = lines.subList(index + 1, index + 1 + end)
                index += end + 2

                val module = info.split(" ").firstOrNull { it.startsWith("deps=") }?.removePrefix("deps=")
                    ?: continue

                val found = claims.getOrPut(module) { mutableSetOf() }
                for (line in body) {
                    coordinate.find(line)?.let { found += normalise("${it.groupValues[1]}:${it.groupValues[2]}:${it.groupValues[3]}") }
                }
            }
        }

        val problems = mutableListOf<String>()

        for ((module, actual) in resolved.get()) {
            val claimed = claims[module]
            if (claimed == null) {
                problems += "  $module: no block on any page declares deps=$module"
                continue
            }

            val real = actual.filter(::documented).map(::normalise).toSet()
            val missing = real - claimed
            val invented = claimed - real

            for (item in missing.sorted()) problems += "  $module: the page does not mention $item"
            for (item in invented.sorted()) problems += "  $module: the page claims $item, which does not resolve"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The documented dependency trees no longer match the build:\n" +
                    problems.joinToString("\n") +
                    "\n\nResolve them with ./gradlew :<module>:dependencies --configuration runtimeClasspath",
            )
        }

        logger.lifecycle("Documented dependencies match the build for ${resolved.get().size} modules.")
    }
}

val checkDocDependencies =
    tasks.register<CheckDocDependencies>("checkDocDependencies") {
        description = "Fails when the dependency trees printed in the documentation have gone stale."
        group = "verification"
        pages.set(rootProject.layout.projectDirectory.dir("docs/src/content/docs"))

        for (module in documentedModules) {
            val configuration =
                configurations.detachedConfiguration(
                    project.dependencies.create(project.project(":$module")),
                )
            resolved.put(
                module,
                configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                    artifacts
                        .mapNotNull { artifact ->
                            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)
                                ?.let { "${it.group}:${it.module}:${it.version}" }
                        }.toSet()
                },
            )
        }
    }

tasks.named("checkDocSamples") { dependsOn(checkDocDependencies) }


/*
 * Runs the generated dispatcher, so the bytes on the page are the bytes Streamlord's own
 * encoder produces — the same encoder the golden-file tests verify on this build.
 */
val generateWireTranscripts =
    tasks.register<JavaExec>("generateWireTranscripts") {
        description = "Writes the exact SSE bytes for every documented event."
        group = "verification"
        dependsOn(tasks.named("compileKotlin"))
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("WireTranscriptsKt")
        args(layout.buildDirectory.dir("wire").get().asFile.absolutePath)
    }


/**
 * Writes the encoder's bytes straight into the `wire=` fences of the pages.
 *
 * The first design left them in a generated folder and injected them with a remark plugin.
 * A negative test killed it: Astro caches a rendered markdown file, so removing the
 * transcript left the build green and, worse, a changed encoder would not have reached the
 * page until somebody happened to edit the prose. Silent staleness is exactly what this
 * whole task exists to prevent.
 *
 * In the markdown the bytes are therefore real text: `git diff` shows a protocol change,
 * a reviewer can see it, and Astro invalidates its cache because the file changed.
 */
abstract class ApplyWireTranscripts : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val transcripts: DirectoryProperty

    /*
     * Neither an input nor an output. The task edits its own source directory, a shape
     * Gradle's model has no room for: declaring it both ways is a dependency cycle, and
     * declaring it either way alone is a lie about what happens. It is cheap, so it runs
     * every time instead.
     */
    @get:Internal
    abstract val pages: DirectoryProperty

    @TaskAction
    fun apply() {
        val bytes =
            transcripts.get().asFile.listFiles().orEmpty()
                .filter { it.extension == "sse" }
                .associate { it.nameWithoutExtension to it.readText().trimEnd('\n') + "\n" }

        var changed = 0

        for (page in pages.get().asFile.walkTopDown().filter { it.extension == "md" }) {
            val lines = page.readLines().toMutableList()
            var index = 0
            var touched = false

            while (index < lines.size) {
                val info = lines[index].takeIf { it.startsWith("```") }?.removePrefix("```")?.trim()
                if (info == null) {
                    index++
                    continue
                }
                val end = lines.drop(index + 1).indexOfFirst { it.startsWith("```") }
                if (end < 0) break

                // Only the output block. The Kotlin block above it carries the same marker
                // and is the source the bytes were made from.
                val name = if (info.startsWith("wire=")) info.removePrefix("wire=").trim() else null
                if (name == null) {
                    index += end + 2
                    continue
                }

                val wanted = bytes[name]
                    ?: throw GradleException("${page.name}: no transcript named '$name' was generated.")

                val current = lines.subList(index + 1, index + 1 + end).joinToString("\n") + "\n"
                if (current != wanted) {
                    repeat(end) { lines.removeAt(index + 1) }
                    lines.addAll(index + 1, wanted.trimEnd('\n').split("\n"))
                    touched = true
                }
                index += wanted.trimEnd('\n').split("\n").size + 2
            }

            if (touched) {
                page.writeText(lines.joinToString("\n") + "\n")
                changed++
            }
        }

        logger.lifecycle(
            if (changed == 0) "Wire transcripts on the pages are current."
            else "Rewrote the wire transcripts on $changed page(s). Commit the change.",
        )
    }
}

val applyWireTranscripts =
    tasks.register<ApplyWireTranscripts>("applyWireTranscripts") {
        description = "Writes the encoder's bytes into the wire= blocks of the documentation."
        group = "verification"
        dependsOn(generateWireTranscripts)
        transcripts.set(layout.buildDirectory.dir("wire"))
        pages.set(rootProject.layout.projectDirectory.dir("docs/src/content/docs"))
        outputs.upToDateWhen { false }
    }

tasks.named("checkDocSamples") { dependsOn(applyWireTranscripts) }
