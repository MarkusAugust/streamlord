plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
    application
}

description =
    "streamlord-live: the Ktor service behind the documentation site. It answers the search " +
        "and drives the live demos, so the pages run the library instead of describing it. Never published."

dependencies {
    implementation(project(":streamlord-core"))
    implementation(project(":streamlord-ktor"))
    implementation(project(":streamlord-html"))
    implementation(project(":streamlord-json-kotlinx"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("io.github.markusaugust.streamlord.demo.MainKt")
}

/*
 * The service as a native image: ~60 MB and a hundred millisecond start against ~300 MB and
 * several seconds on the JVM, which is what makes Railway's sleep usable and the bill under a
 * dollar. The JVM image came first on purpose — proving the deploy chain and proving a native
 * image at the same time is how you end up debugging both at once.
 *
 * src/main/resources/META-INF/native-image/resource-config.json names index.json, because
 * nothing in the bytecode mentions that file — it is read by name at startup — and the image
 * would otherwise ship without it, start cleanly and find nothing.
 *
 * Built in CI: the compile wants 6–8 GB and several minutes, and no GraalVM is installed here.
 * The smoke test that follows the deploy is what makes that acceptable. A binary that starts
 * and cannot serve fails the job before it reaches the site.
 */
graalvmNative {
    binaries {
        named("main") {
            imageName.set("streamlord-live")
            mainClass.set("io.github.markusaugust.streamlord.demo.MainKt")

            // A build that cannot be native should fail, not quietly produce a JVM image
            // with none of the startup or the memory this exists for.
            fallback.set(false)
            verbose.set(true)

            /*
             * An executable, said out loud. Without it the plugin passed --shared and the
             * compile happily produced streamlord-live.so, a shared library with a C header
             * beside it; the failure surfaced two steps later as a Dockerfile unable to find
             * a file that had never been written. Ktor's sample does not set this because it
             * applies io.ktor.plugin, which does.
             */
            sharedLibrary.set(false)

            /*
             * Copied from Ktor's own GraalVM sample rather than assembled by guesswork:
             * github.com/ktorio/ktor-samples/tree/main/graalvm, on the same Kotlin 2.4.20
             * and Ktor 3.6.0 as this module.
             *
             * Three attempts here were spent adding one flag at a time on a theory about
             * what the error meant, and each theory was wrong. The list below is the
             * ecosystem's answer, and it contradicts what I kept reaching for: initializing
             * the whole `kotlin` package at build time is the documented position, not a
             * blunt instrument to be narrowed.
             *
             * ch.qos.logback is in the sample and not here, because this service has no
             * logging backend. Add it with the dependency, not before.
             */
            buildArgs.addAll(
                "--initialize-at-build-time=io.ktor,kotlin",
                "--initialize-at-build-time=org.slf4j.LoggerFactory",
                "--initialize-at-build-time=org.slf4j.helpers.Reporter",
                // The sample names two classes here; this build also reaches
                // kotlinx.io.files.PathsJvmKt and FileSystemJvmKt, which arrive with Ktor
                // and which nothing in this service uses — it reads its index from the
                // classpath, not the filesystem. The package, in the same form the list
                // already uses for io.ktor and kotlin, rather than a fifth and sixth class.
                "--initialize-at-build-time=kotlinx.io",
                "--initialize-at-build-time=kotlinx.serialization.json.Json",
                "--initialize-at-build-time=kotlinx.serialization.json.JsonImpl",
                "--initialize-at-build-time=kotlinx.serialization.json.ClassDiscriminatorMode",
                "--initialize-at-build-time=kotlinx.serialization.modules.SerializersModuleKt",
                "-H:+InstallExitHandlers",
                "-H:+ReportUnsupportedElementsAtRuntime",
                "-H:+ReportExceptionStackTraces",
            )

            /*
             * Mostly static, on Linux: every library the image needs is linked in, zlib and the
             * JDK's own static libraries with it, and only glibc is left to the container. That
             * is the documented option for a distroless base image, which carries glibc and
             * nothing else.
             *
             * Without it the binary links zlib dynamically and the deployment died where no
             * build step could see it: the image built, pushed and deployed green, and the
             * container exited with "libz.so.1: cannot open shared object file". The missing
             * piece only existed at the moment the process started.
             *
             * The flag is version-specific and the documentation is not, which cost a build:
             * graalvm.org/latest names --static-nolibc, and GraalVM for JDK 21 — what CI
             * installs and what the plugin here is pinned for — has no such option and says so
             * by name. It is -H:+StaticExecutableWithDynamicLibC there, per
             * graalvm.org/jdk21/reference-manual/native-image/guides/build-static-executables,
             * and the option is defined under that name in the jdk-21.0.2 sources. Read the
             * guide for the version in the toolchain, not the one at /latest.
             *
             * Linux only: this is a property of ELF linking and native-image rejects it on
             * macOS, where the image is compiled by hand to see that the service runs at all.
             */
            if (System.getProperty("os.name").startsWith("Linux")) {
                buildArgs.add("-H:+StaticExecutableWithDynamicLibC")
            }
        }
    }
}

/**
 * Builds the search index out of the documentation's own markdown.
 *
 * Gradle reads the sources rather than Astro's output, so the two build chains never have to
 * meet: Bun does not need Gradle and Gradle does not need Bun. The index ships inside the
 * jar, so the service needs no network at startup and cannot be half-configured.
 *
 * One record per section, not per page. A hit can then name the heading it was found under
 * and quote the line around it, which is the difference between a search and a list of links.
 */
abstract class BuildSearchIndex : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pages: DirectoryProperty

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun build() {
        val sections = mutableListOf<String>()

        for (page in pages.get().asFile.walkTopDown().filter { it.extension == "md" }.sortedBy { it.name }) {
            val lines = page.readLines()
            val slug = page.nameWithoutExtension

            val title = lines.firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith("title:") }?.substringAfter("title:")?.trim()?.trim('"')
            } ?: slug

            var heading = ""
            val body = StringBuilder()
            var inFrontmatter = false
            var frontmatterSeen = 0

            fun flush() {
                val text = clean(body.toString())
                if (text.isNotBlank()) sections += record(slug, title, heading, text)
                body.setLength(0)
            }

            for (line in lines) {
                if (line.trim() == "---") {
                    frontmatterSeen++
                    inFrontmatter = frontmatterSeen < 2
                    continue
                }
                if (inFrontmatter) continue

                if (line.startsWith("## ") || line.startsWith("### ")) {
                    flush()
                    heading = line.substringAfter("# ").trim()
                    continue
                }
                body.appendLine(line)
            }
            flush()
        }

        target.get().asFile.parentFile.mkdirs()
        target.get().asFile.writeText(sections.joinToString(",\n", "[\n", "\n]\n"))
        logger.lifecycle("Indexed ${sections.size} sections of documentation.")
    }

    /** Markdown down to the words a reader would search for. Code fences stay: readSignals is a word. */
    private fun clean(markdown: String): String =
        markdown
            .replace(Regex("""```[\w=\-"' ]*"""), " ")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""[`*_>|#]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun record(slug: String, title: String, heading: String, text: String): String =
        listOf(
            "slug" to slug,
            "title" to title,
            "heading" to heading,
            "anchor" to anchor(heading),
            "text" to text,
        ).joinToString(", ", "  {", "}") { (key, value) -> "${quote(key)}: ${quote(value)}" }

    /** The id rehype gives a heading, so a hit can link straight to the section. */
    private fun anchor(heading: String): String =
        heading.lowercase()
            .replace(Regex("""[^\w\s-]"""), "")
            .trim()
            .replace(Regex("""\s+"""), "-")

    private fun quote(value: String): String =
        buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
                }
            }
            append('"')
        }
}

val buildSearchIndex =
    tasks.register<BuildSearchIndex>("buildSearchIndex") {
        description = "Builds the documentation search index from the markdown sources."
        group = "build"
        pages.set(rootProject.layout.projectDirectory.dir("docs/src/content/docs"))
        target.set(layout.buildDirectory.file("generated/search-index/index.json"))
    }

/*
 * Its own directory, not build/generated: that one also holds the Java header output, and a
 * resources source dir pointing at it makes processResources depend on compileJava without
 * saying so. Gradle notices and refuses.
 */
sourceSets.main {
    resources.srcDir(buildSearchIndex.map { it.target.get().asFile.parentFile })
}
/*
 * Holds the native-image page to the arguments this module is compiled with.
 *
 * It lives here rather than with the other documentation checks in :docs-samples because the
 * arguments are resolved from the `graalvmNative` extension, which exists only in this project.
 * Resolving them is the point: comparing the text of two build files would pass on a flag that
 * some condition never adds.
 *
 * A block on the page opts in with `buildargs=demo`, beside the `sample=none` that :docs-samples
 * requires on every Kotlin block.
 */
abstract class CheckDocBuildArgs : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val page: RegularFileProperty

    /** What `nativeCompile` would really pass, resolved from the extension. */
    @get:Input
    abstract val resolved: ListProperty<String>

    /**
     * Arguments the page documents under another heading, and which must therefore not appear in
     * the build-file block. The container flag is one: added only on Linux, explained in step 4.
     */
    @get:Input
    abstract val documentedElsewhere: SetProperty<String>

    @TaskAction
    fun check() {
        val expected = resolved.get().filterNot { it in documentedElsewhere.get() }

        val lines = page.get().asFile.readLines()
        var claimed: List<String>? = null
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

            if (info.split(" ").none { it == "buildargs=demo" }) continue
            // Every double-quoted string opening with a dash, so imageName.set("my-service")
            // stays a presentation choice.
            claimed = body.flatMap { line -> Regex("\"(-[^\"]+)\"").findAll(line).map { it.groupValues[1] }.toList() }
        }

        if (claimed == null) {
            throw GradleException(
                "${page.get().asFile.name}: no block is marked buildargs=demo, so the build arguments " +
                    "it prints are unchecked. Mark the build-file block, or delete this task.",
            )
        }

        if (claimed != expected) {
            val missing = expected - claimed.toSet()
            val invented = claimed - expected.toSet()
            throw GradleException(
                buildString {
                    appendLine("The native-image page no longer prints the arguments this module builds with:")
                    for (item in missing) appendLine("  the page does not pass $item")
                    for (item in invented) appendLine("  the page passes $item, which this build does not")
                    if (missing.isEmpty() && invented.isEmpty()) {
                        appendLine("  the same arguments in a different order; the page says \"in this order\"")
                        appendLine("  build: $expected")
                        appendLine("  page:  $claimed")
                    }
                    append("\nThe arguments are in demo/build.gradle.kts. Copy them, do not retype them.")
                },
            )
        }

        logger.lifecycle("The native-image page prints all ${expected.size} of this module's build arguments.")
    }
}

val checkDocBuildArgs =
    tasks.register<CheckDocBuildArgs>("checkDocBuildArgs") {
        description = "Fails when the build arguments printed on the native-image page have gone stale."
        group = "verification"
        page.set(rootProject.layout.projectDirectory.file("docs/src/content/docs/native-image.md"))
        resolved.set(graalvmNative.binaries.named("main").flatMap { it.buildArgs })
        documentedElsewhere.set(setOf("-H:+StaticExecutableWithDynamicLibC"))
    }

tasks.named("check") { dependsOn(checkDocBuildArgs) }
