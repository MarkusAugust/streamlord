import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.markusaugust.streamlord"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }

    // The analysis and its catalog, from the SDK build alongside. The IDE supplies the Kotlin
    // standard library and coroutines, so they are kept out of the plugin's lib folder.
    implementation("io.github.markusaugust.streamlord:streamlord-analysis") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains", module = "annotations")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // The Kotlin bundled with IntelliJ IDEA 2026.1; the plugin must not need a newer standard library.
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
        allWarningsAsErrors.set(true)
        // No bridge methods for the platform's interface defaults: the Plugin Verifier reads them as overrides of deprecated API.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-jvm-default=no-compatibility")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.markusaugust.streamlord"
        name = "Streamlord"
        version = project.version.toString()
        description =
            providers
                .fileContents(layout.projectDirectory.file("README.md"))
                .asText
                .map { readme ->
                    // The Marketplace shows the part of the README between the markers, as HTML.
                    val start = readme.indexOf("<!-- description -->")
                    val end = readme.indexOf("<!-- /description -->")
                    require(start >= 0 && end > start) { "README.md needs <!-- description --> markers" }
                    markdownToHtml(readme.substring(start + "<!-- description -->".length, end))
                }
        changeNotes =
            providers
                .fileContents(layout.projectDirectory.file("CHANGELOG.md"))
                .asText
                .map { changelog ->
                    // The newest release's section.
                    val body = changelog.substringAfter("\n## ", "").substringBefore("\n## ")
                    markdownToHtml(body.substringAfter("\n", body))
                }
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild")
            untilBuild = provider { null }
        }
        vendor {
            name = "MarkusAugust"
            url = "https://github.com/MarkusAugust/streamlord"
        }
    }

    pluginVerification {
        ides {
            // Every released IntelliJ IDEA from the since-build on; EAP builds shift their modules under a plugin's feet.
            select {
                types = listOf(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea)
                channels = listOf(org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel.RELEASE)
                sinceBuild = providers.gradleProperty("platformSinceBuild").get()
            }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("JETBRAINS_CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("JETBRAINS_PRIVATE_KEY")
        password = providers.environmentVariable("JETBRAINS_PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
}

tasks {
    test {
        useJUnit()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // The tests read the SDK's catalog and the VS Code fixtures relative to the repository root.
    withType<Test>().configureEach {
        systemProperty("streamlord.repo", rootProject.projectDir.resolve("../..").canonicalPath)
    }
}

/** Enough Markdown for the README excerpt and the changelog: headings, paragraphs, lists, bold, code, links. */
fun markdownToHtml(md: String): String {
    val out = StringBuilder()
    var inList = false

    fun inline(s: String): String {
        var t = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        t = Regex("""`([^`]+)`""").replace(t) { "<code>${it.groupValues[1]}</code>" }
        t = Regex("""\*\*(.+?)\*\*""").replace(t) { "<b>${it.groupValues[1]}</b>" }
        t = Regex("""\[([^\]]+)]\(([^)]+)\)""").replace(t) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        return t
    }
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotBlank()) out.append("<p>").append(inline(paragraph.toString().trim())).append("</p>\n")
        paragraph.clear()
    }
    for (line in md.lines()) {
        when {
            line.startsWith("- ") -> {
                flush()
                if (!inList) out.append("<ul>\n")
                inList = true
                out.append("<li>").append(inline(line.removePrefix("- "))).append("</li>\n")
            }

            line.startsWith("#") -> {
                flush()
                if (inList) out.append("</ul>\n")
                inList = false
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 4)
                out.append("<h$level>").append(inline(line.trimStart('#').trim())).append("</h$level>\n")
            }

            line.isBlank() -> {
                flush()
                if (inList) out.append("</ul>\n")
                inList = false
            }

            inList && line.startsWith("  ") -> {
                out.insert(out.length - "</li>\n".length, " " + inline(line.trim()))
            }

            else -> {
                paragraph.append(line).append(' ')
            }
        }
    }
    flush()
    if (inList) out.append("</ul>\n")
    return out.toString()
}

/*
 * The HTML live templates, one per attribute, come from the catalog the way the VS Code
 * snippets do, so the two never drift. Generated into the resources at build time.
 */
val generatedResources = layout.buildDirectory.dir("generated/liveTemplates")

val generateHtmlLiveTemplates by tasks.registering {
    val catalogFile = layout.projectDirectory.file("../../catalog/datastar-1.0.4.json")
    val out = generatedResources.map { it.file("liveTemplates/StreamlordHtml.xml") }
    inputs.file(catalogFile)
    outputs.file(out)
    doLast {
        val json = groovy.json.JsonSlurper().parse(catalogFile.asFile) as Map<*, *>
        val attributes = json["attributes"] as List<*>

        fun esc(s: String) =
            s
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "&#10;")
        val sb = StringBuilder("<templateSet group=\"Streamlord HTML\">\n")
        for (a in attributes) {
            a as Map<*, *>
            val name = a["name"] as String
            val keyed = a["keyed"] as? Boolean ?: false
            val keyRequired = a["keyRequired"] as? Boolean ?: false
            val valueKind = a["valueKind"] as String
            val pro = a["pro"] as? Boolean ?: false
            val doc = (if (pro) "Pro: " else "") + (a["doc"] as String)
            val key = if (keyed && keyRequired) ":\$KEY\$" else ""
            val value =
                when (valueKind) {
                    "none" -> ""
                    "signal" -> "=\"\$SIGNAL\$\""
                    "filter" -> "=\"\$FILTER\$\""
                    else -> "=\"\$EXPRESSION\$\""
                }
            sb.append(
                "  <template name=\"data-$name\" value=\"${esc(
                    "data-$name$key$value\$END\$",
                )}\" description=\"${esc(doc)}\" toReformat=\"false\" toShortenFQNames=\"false\">\n",
            )
            if (key.isNotEmpty()) {
                sb.append(
                    "    <variable name=\"KEY\" expression=\"\" defaultValue=\"&quot;${if (name == "on") "click" else "name"}&quot;\" alwaysStopAt=\"true\" />\n",
                )
            }
            when (valueKind) {
                "signal" -> {
                    sb.append(
                        "    <variable name=\"SIGNAL\" expression=\"\" defaultValue=\"&quot;signal&quot;\" alwaysStopAt=\"true\" />\n",
                    )
                }

                "filter" -> {
                    sb.append(
                        "    <variable name=\"FILTER\" expression=\"\" defaultValue=\"&quot;{include: /.*/}&quot;\" alwaysStopAt=\"true\" />\n",
                    )
                }

                "none" -> {}

                else -> {
                    sb.append(
                        "    <variable name=\"EXPRESSION\" expression=\"\" defaultValue=\"&quot;expression&quot;\" alwaysStopAt=\"true\" />\n",
                    )
                }
            }
            sb.append(
                "    <context><option name=\"HTML\" value=\"true\" /><option name=\"KOTLIN_EXPRESSION\" value=\"true\" /></context>\n  </template>\n",
            )
        }
        sb.append(
            "  <template name=\"data-on:click\" value=\"${esc(
                "data-on:click=\"@post('\$PATH\$')\"\$END\$",
            )}\" description=\"Post signals to the server on click.\" toReformat=\"false\" toShortenFQNames=\"false\">\n",
        )
        sb.append("    <variable name=\"PATH\" expression=\"\" defaultValue=\"&quot;/path&quot;\" alwaysStopAt=\"true\" />\n")
        sb.append("    <context><option name=\"HTML\" value=\"true\" /></context>\n  </template>\n")
        sb.append(
            "  <template name=\"data-init\" value=\"${esc(
                "data-init=\"@get('\$PATH\$')\"\$END\$",
            )}\" description=\"Open a stream when the element appears.\" toReformat=\"false\" toShortenFQNames=\"false\">\n",
        )
        sb.append("    <variable name=\"PATH\" expression=\"\" defaultValue=\"&quot;/stream&quot;\" alwaysStopAt=\"true\" />\n")
        sb.append("    <context><option name=\"HTML\" value=\"true\" /></context>\n  </template>\n")
        sb.append("</templateSet>\n")
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeText(sb.toString())
    }
}

sourceSets.main {
    resources.srcDir(generatedResources)
    // The JSON schema of .streamlord/inspector.json is the VS Code extension's, so the file format has one definition.
    resources.srcDir(layout.projectDirectory.dir("../vscode/schemas"))
}

tasks.processResources {
    dependsOn(generateHtmlLiveTemplates)
}
