plugins {
    alias(libs.plugins.kotlin.serialization)
}

description =
    "Compiles every Kotlin example in the documentation against the real modules. " +
        "Never published; it exists so an example cannot go stale without the build saying so."

dependencies {
    implementation(project(":streamlord-core"))
    implementation(project(":streamlord-analysis"))
    implementation(project(":streamlord-html"))
    implementation(project(":streamlord-html-pro"))
    implementation(project(":streamlord-ktor"))
    implementation(project(":streamlord-spring"))
    implementation(project(":streamlord-test"))
    implementation(project(":streamlord-json-kotlinx"))
    implementation(project(":streamlord-json-jackson"))

    // The testing page shows tests, so its samples need what a test needs. They compile here
    // as ordinary main sources, which is enough to catch a renamed assertion or a changed
    // signature; nothing on the page is executed.
    // The JUnit 5 variant by name: kotlin("test") resolves by the test framework of a test
    // source set, and these samples compile as main sources, where kotlin.test.Test would not
    // be the annotation.
    implementation(kotlin("test-junit5"))
    implementation(libs.ktor.server.test.host)
    implementation(libs.spring.test)
    implementation(libs.kotlinx.coroutines.test)

    // The adapters keep their frameworks compileOnly. The samples need them for real.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cio)
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
        import io.github.markusaugust.streamlord.html.pro.*
        // Named rather than starred: the analysis module declares a Tag, an Attribute and a
        // Modifier of its own, and a star import of it would make those ambiguous against
        // kotlinx.html in every sample that builds markup.
        import io.github.markusaugust.streamlord.analysis.Analyzer
        import io.github.markusaugust.streamlord.test.*
        import io.github.markusaugust.streamlord.ktor.*
        import io.github.markusaugust.streamlord.spring.*
        import io.github.markusaugust.streamlord.json.kotlinx.*
        import io.github.markusaugust.streamlord.json.jackson.*
        import kotlinx.coroutines.*
        import kotlinx.coroutines.flow.*
        import kotlinx.html.*
        import kotlinx.serialization.Serializable
        import kotlin.reflect.typeOf
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
                "import io.ktor.http.*\nimport io.ktor.server.application.*\nimport io.ktor.server.routing.*\n" +
                    "import io.ktor.server.response.*\nimport io.ktor.utils.io.*\n$imports\n\n" +
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
            /*
             * A whole controller, written at the top level so the page can show its constructor.
             * That is where the Streamlord bean arrives, and a sample that hides the class
             * hides the one line a reader has to get right.
             */
            "spring-controller" ->
                "import org.springframework.web.bind.annotation.*\n" +
                    "import jakarta.servlet.http.HttpServletRequest\n" +
                    "import jakarta.servlet.http.HttpServletResponse\n$imports\n\n$body\n"
            /*
             * A test class. The client and test-host imports live here rather than in the shared
             * block because `io.ktor.client.request.get` and `io.ktor.server.routing.get` would
             * then both be in scope of every Ktor sample on every other page, for no gain.
             */
            "test" ->
                "import kotlin.test.*\n" +
                    "import io.ktor.server.application.*\nimport io.ktor.server.routing.*\n" +
                    "import io.ktor.server.testing.*\n" +
                    "import io.ktor.server.engine.*\nimport io.ktor.server.cio.*\nimport io.ktor.server.response.*\n" +
                    "import io.ktor.http.HttpStatusCode\nimport java.net.URI\n" +
                    "import io.ktor.client.request.*\nimport io.ktor.client.statement.*\n" +
                    "import org.springframework.mock.web.*\n" +
                    "import io.github.markusaugust.streamlord.analysis.*\n$imports\n\n" +
                    "class $name {\n$body\n}\n"
            "html" -> "$imports\n\nfun kotlinx.html.FlowContent.$name() {\n$body\n}\n"
            "statements" -> "$imports\n\nsuspend fun $name() {\n$body\n}\n"
            "stream" -> "$imports\n\nsuspend fun DatastarStream.$name() {\n$body\n}\n"
            "declarations" -> "$imports\n\n$body\n"
            else -> null
        }

    private val known =
        listOf("declarations", "html", "ktor-application", "ktor-routing", "spring", "spring-controller", "statements", "stream", "test")

    /**
     * A handler method that takes the Streamlord bean as a parameter. It compiles, and Spring
     * then treats the parameter as a model attribute and constructs a fresh default instance,
     * so the codec and the guard on the real bean are silently ignored. The bean has to come
     * through the constructor.
     */
    private val handlerTakingTheBean =
        Regex("""@(?:Get|Post|Put|Patch|Delete|Request)Mapping\b(?:(?!\bfun\b)[\s\S])*?\bfun\s+\w+\s*\([^)]*:\s*Streamlord\b""")

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

                if (context.startsWith("spring") && handlerTakingTheBean.containsMatchIn(body)) {
                    problems +=
                        Problem(
                            page.name,
                            fenceLine,
                            "a handler method takes Streamlord as a parameter. Spring builds a new default instance " +
                                "for it instead of injecting the bean; take the bean in the controller's constructor.",
                        )
                    continue
                }

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
         * transcript's name, and a generated `main` naming them is easier to debug than a
         * classpath scan.
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
 * One name to run, and one that reads clearly in a CI log.
 *
 * `check` has to be told about it. `build` reaches `compileKotlin` through `assemble`, so the
 * examples compile either way, but the dependency trees, the transcripts and the coordinates
 * hang off this task and nothing else asks for it.
 */
tasks.register("checkDocSamples") {
    description = "Checks everything the documentation asserts: examples, coordinates, transcripts, trees."
    group = "verification"
    dependsOn(tasks.named("compileKotlin"))

    /*
     * The one documentation check that cannot live here. The native-image page prints the build
     * arguments of :demo, and those are resolved from a Gradle extension that exists only in that
     * project. It is named here anyway, so that the task whose description says "everything the
     * documentation asserts" is not quietly missing one.
     */
    dependsOn(":demo:checkDocBuildArgs")
}

tasks.named("check") { dependsOn(tasks.named("checkDocSamples")) }


/*
 * The dependency trees the install page prints are resolved facts, and a fact in markdown rots
 * the moment someone bumps a version in libs.versions.toml. This resolves each module the way a
 * consumer would and fails when the page disagrees.
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
 * encoder produces, the same encoder the golden-file tests verify on this build.
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
 * Into the markdown itself, not a generated folder injected at render time: Astro caches a
 * rendered markdown file, so a changed encoder would not reach the page until somebody edited
 * the prose. As real text, `git diff` shows a protocol change and Astro invalidates its cache
 * because the file changed.
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


/*
 * Writes the project's version into every coordinate the documentation prints, so that a version
 * bump cannot leave the pages naming a superseded release.
 *
 * Both spellings are covered, the Gradle coordinate and the Maven element, because the install
 * page carries a tab for each.
 *
 * It does not close the window between bumping the version and the tag finishing its publish,
 * during which the pages name a release Maven Central has not seen yet. Closing that would mean
 * tracking the last successful publish separately; bumping and tagging in one push, as the
 * README prescribes, keeps the window short instead.
 */
abstract class ApplyProjectVersion : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    /** The Maven group. Not called `group`: a Task already has one, and it is its own. */
    @get:Input
    abstract val coordinateGroup: Property<String>

    /** Rewritten in place, so neither an input nor an output. See ApplyWireTranscripts. */
    @get:Internal
    abstract val pages: DirectoryProperty

    @get:Internal
    abstract val extraPages: ConfigurableFileCollection

    @TaskAction
    fun apply() {
        val version = version.get()
        val group = coordinateGroup.get()

        val gradleCoordinate = Regex("""(${Regex.escape(group)}:[\w-]+:)[\w.\-]+""")
        val mavenDependency = Regex(
            """(<groupId>${Regex.escape(group)}</groupId>\s*<artifactId>[\w-]+</artifactId>\s*<version>)[^<]+(</version>)""",
        )

        val files =
            pages.get().asFile.walkTopDown().filter { it.extension == "md" } + extraPages.files
        var changed = 0

        for (page in files) {
            val before = page.readText()
            val after =
                before
                    .replace(gradleCoordinate) { "${it.groupValues[1]}$version" }
                    .replace(mavenDependency) { "${it.groupValues[1]}$version${it.groupValues[2]}" }

            if (after != before) {
                page.writeText(after)
                changed++
            }
        }

        logger.lifecycle(
            if (changed == 0) "Documented coordinates already name $version."
            else "Rewrote the coordinates to $version on $changed file(s). Commit the change.",
        )
    }
}

val applyProjectVersion =
    tasks.register<ApplyProjectVersion>("applyProjectVersion") {
        description = "Writes the project's version into every coordinate the documentation prints."
        group = "verification"
        version.set(rootProject.version.toString())
        coordinateGroup.set(rootProject.group.toString())
        pages.set(rootProject.layout.projectDirectory.dir("docs/src/content/docs"))
        extraPages.from(rootProject.layout.projectDirectory.file("README.md"))
        outputs.upToDateWhen { false }
    }

tasks.named("checkDocSamples") { dependsOn(applyProjectVersion) }
