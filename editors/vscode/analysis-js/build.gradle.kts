import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin

/*
 * The analysis and the part of core it reads, compiled to JavaScript for the VS Code extension:
 * the same source directories the JVM modules compile, and their tests run on Node. Nothing here
 * is published; `npm run build` in editors/vscode bundles the library this project writes.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Never published: a version of its own keeps the release's out of kotlin-js-store/package-lock.json.
version = "0.0.0"

// The generated catalog sources are tasks of the JVM modules.
evaluationDependsOn(":streamlord-core")
evaluationDependsOn(":streamlord-analysis")

val core = project(":streamlord-core")
val analysis = project(":streamlord-analysis")
val floor = KotlinVersion.fromVersion(libs.versions.kotlinFloor.get().substringBeforeLast('.'))

// The Node and npm on the PATH, which the extension's own build needs anyway, and no second one
// downloaded: for this project's tasks and for the npm install the plugin runs from the root.
plugins.withType<NodeJsPlugin> {
    extensions.getByType<NodeJsEnvSpec>().download.set(false)
}
rootProject.plugins.withType<NodeJsRootPlugin> {
    rootProject.extensions.getByType<NodeJsEnvSpec>().download.set(false)
}

kotlin {
    js {
        nodejs {
            testTask {
                // Read by path, so a change to a case alone runs the tests again.
                val root = rootProject.layout.projectDirectory
                inputs.dir(root.dir("editors/vscode/test/fixtures")).withPropertyName("editorFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
                inputs.dir(root.dir("conformance")).withPropertyName("conformance").withPathSensitivity(PathSensitivity.RELATIVE)
                inputs.dir(root.dir("catalog")).withPropertyName("catalog").withPathSensitivity(PathSensitivity.RELATIVE)
                useMocha { timeout = "120s" }
            }
        }
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        // Common, so the annotations the JVM alone reads (@JvmInline, @JvmStatic) are allowed and ignored.
        getByName("commonMain") {
            kotlin.srcDir(core.layout.projectDirectory.dir("src/main/kotlin"))
            kotlin.srcDir(analysis.layout.projectDirectory.dir("src/main/kotlin"))
            kotlin.srcDir(core.tasks.named("generateCatalogNames"))
            kotlin.srcDir(analysis.tasks.named("generateCatalogText"))
            // What the analysis never reads, the server side of core, and what each module asks of the JVM.
            kotlin.exclude(
                "**/core/application/**",
                "**/core/port/**",
                "**/core/protocol/SseEncoder.kt",
                "**/*Jvm.kt",
            )
            dependencies {
                // @Language on the parameters, as in core; annotations alone, nothing runs.
                implementation(libs.jetbrains.annotations)
            }
        }
        // The two modules' own tests, as they are. A test whose subject or oracle is the JVM is named
        // *JvmTest and stays there, and so do the two named for why and the tests of core's server
        // side: the stream, the encoder, the guards on what it sends and the reading of a request.
        getByName("jsTest") {
            kotlin.srcDir(analysis.layout.projectDirectory.dir("src/test/kotlin"))
            kotlin.srcDir(core.layout.projectDirectory.dir("src/test/kotlin"))
            kotlin.srcDir(core.layout.projectDirectory.dir("src/testCommon/kotlin"))
            kotlin.exclude(
                "**/*JvmTest.kt",
                // Reads a project's files.
                "**/analysis/AnalyzeProjectTest.kt",
                // Its oracle is the kotlinx.html DSL.
                "**/analysis/DataSignalsSecondArgumentTest.kt",
                "**/core/DatastarStreamTest.kt",
                "**/core/ElementsGuardTest.kt",
                "**/core/ElementsResponseViewTransitionSelectorTest.kt",
                "**/core/EncodeFlowTest.kt",
                "**/core/GoldenConformanceTest.kt",
                "**/core/HeartbeatTest.kt",
                "**/core/ProtocolAsTheClientTakesItTest.kt",
                "**/core/ReadSignalsGuardTest.kt",
                "**/core/ReadSignalsTest.kt",
                "**/core/ResponseAsTheClientReadsTest.kt",
                "**/core/ScriptAndSignalsContextTest.kt",
                "**/core/SendLatestTest.kt",
                "**/core/SseEncoderTest.kt",
                "**/core/StreamAuthorisationTest.kt",
                "**/core/ToJsonValueTest.kt",
                "**/core/UrlHelpersTest.kt",
                "**/core/WireLimitsTest.kt",
            )
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
    compilerOptions {
        languageVersion.set(floor)
        apiVersion.set(floor)
        optIn.add("io.github.markusaugust.streamlord.core.InternalStreamlordApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

// The JVM tests of the two modules hold bounds on time, and the Kotlin/JS compiler takes every
// core it is given: in a build that runs both, it waits for them.
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("KotlinJs") }.configureEach {
    mustRunAfter(core.tasks.withType<Test>(), analysis.tasks.withType<Test>())
}

// Nothing here is assembled for anyone but the extension, which asks for `port`: a build of the
// repository compiles this project once, for its tests.
tasks.named("assemble") { setDependsOn(emptyList<Any>()) }

// The plugin follows the library's compile with a reading of its types by a TypeScript it installs
// from npm. The extension's own typecheck reads them, so its build asks the registry for nothing.
tasks.named("compileProductionLibraryKotlinJs") { setFinalizedBy(emptyList<Any>()) }

/*
 * The library as the extension imports it: the two modules and the TypeScript types the compiler
 * wrote for the port. Kotlin marks each interface it exports with a property no TypeScript object
 * has, so that none is implemented there; the port's interfaces are plain data, which the
 * extension writes too (a request from the inspector's page, a frame in a test), so the mark is
 * taken out.
 */
val port =
    tasks.register("port") {
        description = "Writes the library and its TypeScript types where the VS Code extension imports them."
        group = "build"
        val library = tasks.named<KotlinJsIrLink>("compileProductionLibraryKotlinJs").flatMap { it.destinationDirectory }.map { it.asFile }
        val into = layout.buildDirectory.dir("port")
        inputs.dir(library).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(into)
        doLast {
            val out = into.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            for (name in listOf("streamlord-analysis-js.mjs", "kotlin-kotlin-stdlib.mjs")) library.get().resolve(name).copyTo(out.resolve(name))
            val mark = Regex("""\n *readonly __doNotUseOrImplementIt: \{[^}]*\};""")
            out.resolve("streamlord-analysis-js.d.mts").writeText(mark.replace(library.get().resolve("streamlord-analysis-js.d.mts").readText(), ""))
        }
    }
