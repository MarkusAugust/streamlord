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
