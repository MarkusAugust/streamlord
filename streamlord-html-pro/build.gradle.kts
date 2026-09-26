description = "Streamlord HTML Pro: opt-in kotlinx.html helpers for the attribute names and actions of Datastar Pro. Contains no Datastar Pro code; a Pro license and the Pro bundle are yours to bring."

dependencies {
    api(project(":streamlord-html"))

    testImplementation(kotlin("test"))
}

/*
 * The scratch folder for trying the editor tooling. Git-ignored, absent in CI; when present it
 * compiles as part of this module's tests so the Kotlin language server can resolve the DSL.
 */
sourceSets.test {
    kotlin.srcDir(rootProject.file("playground"))
}
