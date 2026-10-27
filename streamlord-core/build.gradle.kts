description = "Streamlord core: the Datastar protocol, pure and without allegiance to any framework."

dependencies {
    api(libs.kotlinx.coroutines.core)

    // @Language on the HTML, JSON and JavaScript parameters, for IntelliJ. compileOnly: it never
    // reaches a consumer from here, and kotlin-stdlib ships the same artifact anyway.
    compileOnly(libs.jetbrains.annotations)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

/*
 * A consumer on Spring Boot runs on Boot's pin of coroutines, 1.10.2, which is the compile-time
 * version the default test task covers; a build without Boot's dependency management resolves the
 * highest on its classpath, Ktor's among them. So the tests also run on the newest release.
 */
val coroutinesNewestVersion = libs.versions.coroutinesNewest.get()

val newestCoroutinesTestRuntimeClasspath: Configuration =
    configurations.create("newestCoroutinesTestRuntimeClasspath") {
        extendsFrom(configurations.testRuntimeClasspath.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        }
        resolutionStrategy.eachDependency {
            val coroutines = requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")
            if (coroutines) useVersion(coroutinesNewestVersion)
        }
    }

val testNewestCoroutines =
    tasks.register<Test>("testNewestCoroutines") {
        description = "Runs the tests against kotlinx-coroutines $coroutinesNewestVersion."
        group = "verification"
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().output + sourceSets.main.get().output + newestCoroutinesTestRuntimeClasspath
        useJUnitPlatform()
    }

tasks.check { dependsOn(testNewestCoroutines) }

// The tests read these by path, so a change there must rerun them and not be served from the cache.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("conformance/client"))
    inputs.dir(rootProject.layout.projectDirectory.dir("conformance/dom"))
}
