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
val playground: SourceSet by sourceSets.creating {
    kotlin.srcDir(rootProject.file("playground"))
}

dependencies {
    // Only so the editor can resolve it. Nothing here is compiled by a normal build.
    "playgroundImplementation"(project(":streamlord-html-pro"))
    "playgroundImplementation"(project(":streamlord-ktor"))
    "playgroundImplementation"(libs.ktor.server.core)
    "playgroundImplementation"(libs.kotlinx.coroutines.core)
}
