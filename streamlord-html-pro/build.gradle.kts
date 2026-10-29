description = "Streamlord HTML Pro: opt-in kotlinx.html helpers for the attribute names and actions of Datastar Pro. Contains no Datastar Pro code; a Pro license and the Pro bundle are yours to bring."

dependencies {
    api(project(":streamlord-html"))

    testImplementation(kotlin("test"))
}

/*
 * The scratch folder for trying the editor tooling. Git-ignored, absent in CI.
 *
 * It is the bench the editors' diagnostics are tried against, so most of what lives there is
 * deliberately wrong: `APPEND` without a selector, a `<p>` that is never closed, `__debunce`
 * for `__debounce`. Code broken on purpose must not be able to fail the build, so it is a
 * source set of its own. IntelliJ resolves the DSL inside it from a real classpath, while
 * `assemble` and `check` never reach a source set nothing else asks for.
 *
 * `./gradlew :streamlord-html-pro:playgroundClasses` compiles it on request, and will usually
 * fail. That is the bench reporting the errors it was written to provoke.
 */
val playground: SourceSet =
    sourceSets.create("playground") {
        kotlin.srcDir(rootProject.file("playground"))
    }

dependencies {
    // Only so the editor can resolve it. Nothing here is compiled by a normal build.
    "playgroundImplementation"(project(":streamlord-html-pro"))
    "playgroundImplementation"(project(":streamlord-ktor"))
    "playgroundImplementation"(libs.ktor.server.core)
    "playgroundImplementation"(libs.kotlinx.coroutines.core)
}

// The tests read these by path, so a change there must rerun them and not be served from the cache.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("catalog"))
    inputs.dir(rootProject.layout.projectDirectory.dir("conformance/client"))
}

/*
 * What the client does with the HTML the DSL writes, under conformance/dsl: the tests write each
 * case's input under updateClientReadings, `npm run dsl` in conformance/generate fills in the
 * client's reading, and every other test run compares the two with what the case means.
 */
val dslDir = rootProject.layout.projectDirectory.dir("conformance/dsl")

tasks.withType<Test>().configureEach { systemProperty("streamlord.dslDir", dslDir.asFile.path) }
tasks.withType<Test>().matching { it.name != "updateClientReadings" }.configureEach {
    inputs.dir(dslDir)
    // A reading another dsl.mjs took is refused, so a change to the script alone runs the tests again.
    inputs.file(rootProject.layout.projectDirectory.file("conformance/generate/dsl.mjs"))
}

tasks.register<Test>("updateClientReadings") {
    description = "Rewrites the inputs of the fixtures under conformance/dsl from what the DSL writes now."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath =
        sourceSets.test
            .get()
            .runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*AsTheClientReadsItTest") }
    systemProperty("streamlord.updateClientReadings", "true")
    // It rewrites the fixtures it does not declare as inputs, so it must never be up to date or come from the cache.
    doNotTrackState("rewrites conformance/dsl")
}
