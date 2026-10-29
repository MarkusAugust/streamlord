description = "Streamlord Spring adapter: the Shield. Spring and the servlet API are compileOnly; bring your own. Compiled and tested against Spring Framework 7.0 (Boot 4)."

dependencies {
    api(project(":streamlord-core"))
    compileOnly(libs.spring.web)
    compileOnly(libs.jetbrains.annotations) // @Language on the response helpers, see streamlord-core
    compileOnly(libs.spring.webmvc)
    compileOnly(libs.jakarta.servlet)

    testImplementation(kotlin("test"))
    testImplementation(libs.spring.web)
    testImplementation(libs.spring.webmvc)
    testImplementation(libs.spring.test)
    testImplementation(libs.assertj.core)
    testImplementation(project(":streamlord-test"))
    testImplementation(libs.spring.webflux)
    testImplementation(libs.jakarta.servlet)
    testImplementation(libs.reactor.core)
    testImplementation(libs.tomcat.embed.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(project(":streamlord-json-jackson"))
    testImplementation(libs.jackson.kotlin)
}

/*
 * Boot pins coroutines, and a consumer runs on Boot's pin whatever this module was compiled
 * against: 1.10.2 under Boot 4, which is the compile-time version, so the default test task
 * covers it. A Gradle build without Boot's dependency management resolves the highest coroutines
 * on the classpath instead, so the adapter also has to run on a release newer than its floor.
 */
val coroutinesNewestVersion = libs.versions.coroutinesNewest.get()

fun DependencyResolveDetails.useCoroutines(version: String) {
    if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")) useVersion(version)
}

val newestCoroutinesTestRuntimeClasspath: Configuration = configurations.create("newestCoroutinesTestRuntimeClasspath") {
    extendsFrom(configurations.testRuntimeClasspath.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
    resolutionStrategy.eachDependency { useCoroutines(coroutinesNewestVersion) }
}

val testNewestCoroutines = tasks.register<Test>("testNewestCoroutines") {
    description = "Runs the Spring adapter tests against kotlinx-coroutines $coroutinesNewestVersion."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + newestCoroutinesTestRuntimeClasspath
    useJUnitPlatform()
}

tasks.check { dependsOn(testNewestCoroutines) }

/*
 * The approved wire transcripts under conformance/wire: what the adapter answers a fixed set of
 * requests on WebMVC and WebFlux, both on an embedded Tomcat, committed as text. Every test task
 * compares; updateTranscripts rewrites.
 */
val wireDir = rootProject.layout.projectDirectory.dir("conformance/wire")

tasks.withType<Test>().configureEach { systemProperty("streamlord.wireDir", wireDir.asFile.path) }
tasks.withType<Test>().matching { it.name != "updateTranscripts" }.configureEach { inputs.dir(wireDir) }

tasks.register<Test>("updateTranscripts") {
    description = "Rewrites the approved wire transcripts under conformance/wire from what the Spring adapter answers now."
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
    filter { includeTestsMatching("*WireTranscriptTest") }
    systemProperty("streamlord.updateTranscripts", "true")
    // It rewrites the transcripts it does not declare as inputs, so it must never be up to date or come from the cache.
    doNotTrackState("rewrites conformance/wire")
}
