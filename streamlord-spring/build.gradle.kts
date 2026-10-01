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
 */
val spring7Version = libs.versions.spring7.get()
val servlet61Version = libs.versions.servlet61.get()

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
    }
}

val testSpring7 = tasks.register<Test>("testSpring7") {
    description = "Runs the Spring adapter tests against Spring Framework $spring7Version (Boot 4)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + spring7TestRuntimeClasspath
    useJUnitPlatform()
}

tasks.check { dependsOn(testSpring7) }
