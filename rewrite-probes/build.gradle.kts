description =
    "Compiles HTML strings as they are written and as each fix of the analysis leaves them, and runs both. " +
        "Never published; it holds every rewrite to what the Kotlin compiler and core's interpolate make of it."

val generator: SourceSet by sourceSets.creating

dependencies {
    "generatorImplementation"(project(":streamlord-analysis"))
    testImplementation(project(":streamlord-core"))
    testImplementation(kotlin("test"))
}

kotlin.compilerOptions.optIn.add("io.github.markusaugust.streamlord.core.InternalStreamlordApi")

/*
 * The cases are text: rewrite-probes.json, and every cell of hole-matrix.json. The generator asks
 * the analysis for each fix and each context action, and writes the function before and after each
 * as Kotlin, which the test source set compiles against core. A fix that leaves code the compiler
 * refuses fails the build there, with the case named in the file.
 */
val cases = rootProject.layout.projectDirectory.dir("editors/vscode/test/fixtures/cases")
val probes = layout.buildDirectory.dir("generated/probes")
val generateProbes =
    tasks.register<JavaExec>("generateProbes") {
        description = "Writes each probe before and after every fix the analysis offers for it."
        group = "build"
        classpath = generator.runtimeClasspath
        mainClass.set("io.github.markusaugust.streamlord.probes.GenerateKt")
        inputs.files(cases.file("rewrite-probes.json"), cases.file("hole-matrix.json")).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(probes)
        args(cases.asFile.path, probes.get().asFile.path)
    }

kotlin.sourceSets.test {
    kotlin.srcDir(generateProbes.map { probes })
}
