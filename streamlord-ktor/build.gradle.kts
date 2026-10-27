plugins {
    alias(libs.plugins.kotlin.serialization)
}

description = "Streamlord Ktor adapter: the Sword. Brings nothing you do not already carry; Ktor is compileOnly."

dependencies {
    api(project(":streamlord-core"))
    compileOnly(libs.ktor.server.core)
    compileOnly(libs.jetbrains.annotations) // @Language on the response helpers, see streamlord-core

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":streamlord-json-kotlinx"))
    testImplementation(project(":streamlord-html"))
    // A real server on a real socket, for the tests the test host cannot serve: it hands a
    // streamed response to the client only once the stream has ended.
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.tomcat.jakarta)
    testImplementation(libs.ktor.server.jetty.jakarta)
    testImplementation(project(":streamlord-test"))
    testImplementation(libs.ktor.server.compression)
    testImplementation(libs.ktor.server.status.pages)
}

/*
 * Ktor is compileOnly, so a consumer runs the adapter on whatever Ktor 3 they have. Compiled
 * against the newest, it can still reach for something only the newest has, an inline helper's
 * constructor among them, and that fails at runtime only. So the tests are compiled again the
 * way a consumer's code would be, against the oldest Ktor 3 the adapter promises, and run on it
 * against the adapter's classes as compiled against the newest.
 */
val ktorOldestVersion = libs.versions.ktorOldest.get()

val oldestKtorTest: SourceSet = sourceSets.create("oldestKtorTest") {
    kotlin.srcDir("src/test/kotlin")
    // HttpRequestLifecycle came in Ktor 3.4.0.
    kotlin.exclude("**/RequestLifecycleTest.kt")
    runtimeClasspath += sourceSets.main.get().output
}

configurations.named("oldestKtorTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
dependencies {
    // Ktor 3.0's Netty, Jetty and Tomcat raised past their advisories, the way install.md tells a
    // user on Ktor 3.0 to.
    "oldestKtorTestImplementation"(platform(libs.netty.bom.oldestKtor))
    "oldestKtorTestImplementation"(platform(libs.jetty.bom.oldestKtor))
}
configurations.named("oldestKtorTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
val tomcatOldestKtorVersion = libs.versions.tomcatOldestKtor.get()
listOf("oldestKtorTestCompileClasspath", "oldestKtorTestRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.ktor") useVersion(ktorOldestVersion)
            // Tomcat has no BOM.
            if (requested.group.startsWith("org.apache.tomcat") && requested.version?.startsWith("10.1.") == true) {
                useVersion(tomcatOldestKtorVersion)
            }
        }
    }
}

kotlin.target.compilations.named("oldestKtorTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

val testOldestKtor = tasks.register<Test>("testOldestKtor") {
    description = "Runs the Ktor adapter tests, compiled against Ktor $ktorOldestVersion, on it."
    group = "verification"
    testClassesDirs = oldestKtorTest.output.classesDirs
    classpath = oldestKtorTest.runtimeClasspath
    useJUnitPlatform()
    systemProperty("streamlord.ktorOldest", ktorOldestVersion)
}

tasks.check { dependsOn(testOldestKtor) }

/*
 * Ktor's javax servlet engine, ktor-server-tomcat on Tomcat 9, has classes of other names than the
 * jakarta one the adapter reaches into, and its Tomcat cannot share a classpath with Tomcat 11, so
 * its tests have a source set and a classpath of their own.
 */
val javaxTomcatTest: SourceSet = sourceSets.create("javaxTomcatTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    "javaxTomcatTestImplementation"(kotlin("test-junit5"))
    "javaxTomcatTestImplementation"(project(":streamlord-core"))
    "javaxTomcatTestImplementation"(libs.ktor.server.core)
    "javaxTomcatTestImplementation"(libs.ktor.server.tomcat)
}

val testJavaxTomcat = tasks.register<Test>("testJavaxTomcat") {
    description = "Runs the Ktor adapter tests for Ktor's javax servlet engine, on Tomcat 9."
    group = "verification"
    testClassesDirs = javaxTomcatTest.output.classesDirs
    classpath = javaxTomcatTest.runtimeClasspath
}

tasks.check { dependsOn(testJavaxTomcat) }
