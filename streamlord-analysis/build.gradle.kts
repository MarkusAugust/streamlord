description = "Streamlord Analysis: editor-independent checks of Datastar expressions and markup in Kotlin and HTML."

dependencies {
    // The strict JSON parser that reads the catalog; core brings nothing but the Kotlin standard
    // library and coroutines, so a consumer of the analysis gets no library it did not have.
    api(project(":streamlord-core"))

    testImplementation(kotlin("test"))
}

// The catalog is the single source of truth and lives once, at the repository root.
sourceSets.main {
    resources.srcDir(rootProject.file("catalog"))
}
