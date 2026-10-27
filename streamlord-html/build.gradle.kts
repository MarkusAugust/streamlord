description = "Streamlord HTML: kotlinx.html DSL with Datastar attributes, actions and expressions."

dependencies {
    api(project(":streamlord-core"))
    api(libs.kotlinx.html)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// The tests read these by path, so a change there must rerun them and not be served from the cache.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("conformance/client"))
}
