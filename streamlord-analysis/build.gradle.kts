description = "Streamlord Analysis: editor-independent checks of Datastar expressions and markup in Kotlin and HTML."

dependencies {
    // The strict JSON parser that reads the catalog; core brings nothing but the Kotlin standard
    // library and coroutines, so a consumer of the analysis gets no library it did not have.
    api(project(":streamlord-core"))

    testImplementation(kotlin("test"))
    // DataSignalsSecondArgumentTest compiles DSL calls, so the Kotlin compiler is its oracle.
    testImplementation(project(":streamlord-html"))
}

// The catalog is the single source of truth and lives once, at the repository root.
sourceSets.main {
    resources.srcDir(rootProject.file("catalog"))
}

// The tests read the cases the VS Code extension shares with them: the corpus, the SSE corpus, the
// second argument of dataSignals and the templates. Declared, so a change to a case alone runs the
// tests again rather than finding them up to date or cached.
tasks.test {
    inputs
        .dir(rootProject.layout.projectDirectory.dir("editors/vscode/test/fixtures"))
        .withPropertyName("editorFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// The tests read these by path, so a change there must rerun them and not be served from the cache.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("conformance/client"))
    inputs.dir(rootProject.layout.projectDirectory.dir("conformance/dsl"))
}
