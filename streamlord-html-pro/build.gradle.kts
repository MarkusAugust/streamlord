description = "Streamlord HTML Pro: opt-in kotlinx.html helpers for the attribute names and actions of Datastar Pro. Contains no Datastar Pro code; a Pro license and the Pro bundle are yours to bring."

dependencies {
    api(project(":streamlord-html"))

    testImplementation(kotlin("test"))
}

/*
 * The scratch folder for trying the editor tooling. Git-ignored, absent in CI.
 *
 * It used to join this module's test sources, so that the Kotlin language server could
 * resolve the DSL inside it. That made `./gradlew build` red on any machine where the
 * folder had content, and not by accident: most of what lives there is deliberately
 * wrong. It is the bench the editors' diagnostics are tried against, with the expected
 * squiggle written in a comment beside each line — `APPEND` without a selector, a `<p>`
 * that is never closed, `__debunce` for `__debounce`. Code that is broken on purpose must
 * not be able to fail the build, and a build that is always red teaches everyone to stop
 * reading it.
 *
 * It is therefore a source set of its own. IntelliJ sees a source root with a real
 * classpath and resolves the DSL exactly as before, while `assemble` and `check` never
 * reach it: neither depends on a source set that nothing else asks for.
 *
 * `./gradlew :streamlord-html-pro:playgroundClasses` compiles it if you ask, and will
 * usually fail — that is the bench reporting the errors it was written to provoke, which
 * is occasionally what you want to see.
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
