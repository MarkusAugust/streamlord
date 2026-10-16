description =
    "Streamlord test assertions: read a Datastar response back into events and assert on what " +
        "it meant. Binds no test framework, so it works under kotlin.test, JUnit, Kotest or TestNG alike."

dependencies {
    api(project(":streamlord-core"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

/*
 * A consumer on Spring Boot runs on Boot's pin of coroutines, 1.10.2, which is the compile-time
 * version the default test task covers; a build without Boot's dependency management resolves the
 * highest on its classpath, Ktor's among them. So the tests also run on the newest release.
 */
val coroutinesNewestVersion = libs.versions.coroutinesNewest.get()

val newestCoroutinesTestRuntimeClasspath: Configuration = configurations.create("newestCoroutinesTestRuntimeClasspath") {
    extendsFrom(configurations.testRuntimeClasspath.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")) useVersion(coroutinesNewestVersion)
    }
}

val testNewestCoroutines = tasks.register<Test>("testNewestCoroutines") {
    description = "Runs the tests against kotlinx-coroutines $coroutinesNewestVersion."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + newestCoroutinesTestRuntimeClasspath
    useJUnitPlatform()
}

tasks.check { dependsOn(testNewestCoroutines) }
