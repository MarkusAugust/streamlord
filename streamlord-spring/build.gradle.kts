description = "Streamlord Spring adapter: the Shield. Spring and the servlet API are compileOnly; bring your own. Compiled against Spring Framework 6.2 (Boot 3), tested against 6.2 and 7.0 (Boot 4)."

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
    testImplementation(libs.jakarta.servlet)
    testImplementation(libs.reactor.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":streamlord-json-jackson"))
    testImplementation(libs.jackson.kotlin)
}

/*
 * The Shield must hold in both empires: Spring Boot 3 (Framework 6.2, Servlet 6.0) and
 * Spring Boot 4 (Framework 7.0, Servlet 6.1). The default test task runs against the
 * compile-time versions; this second task swaps in the newer ones at runtime.
 *
 * Coroutines are swapped with them, because Boot pins those too and a consumer runs on Boot's
 * pin whatever this module was compiled against: 1.8.1 under Boot 3, which is the compile-time
 * version, and 1.10.2 under Boot 4.
 */
val spring7Version = libs.versions.spring7.get()
val servlet61Version = libs.versions.servlet61.get()
val coroutinesBoot4Version = libs.versions.coroutinesBoot4.get()
val coroutinesNewestVersion = libs.versions.coroutinesNewest.get()

fun DependencyResolveDetails.useCoroutines(version: String) {
    if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")) useVersion(version)
}

val spring7TestRuntimeClasspath: Configuration = configurations.create("spring7TestRuntimeClasspath") {
    extendsFrom(configurations.testRuntimeClasspath.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
    resolutionStrategy.eachDependency {
        if (requested.group == "org.springframework") useVersion(spring7Version)
        if (requested.group == "jakarta.servlet") useVersion(servlet61Version)
        useCoroutines(coroutinesBoot4Version)
    }
}

val testSpring7 = tasks.register<Test>("testSpring7") {
    description = "Runs the Spring adapter tests against Spring Framework $spring7Version (Boot 4)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + spring7TestRuntimeClasspath
    useJUnitPlatform()
}

/*
 * The other direction. A Gradle build without Boot's dependency management resolves the highest
 * coroutines on the classpath, so the adapter also has to run on a release newer than its floor.
 */
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

tasks.check { dependsOn(testSpring7, testNewestCoroutines) }
