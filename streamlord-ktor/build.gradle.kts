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
    testImplementation(project(":streamlord-test"))
    testImplementation(libs.ktor.server.compression)
}

/*
 * Ktor is compileOnly, so a consumer runs the adapter on whatever Ktor 3 they have. Compiled
 * against the newest, it can still reach for something only the newest has, an inline helper's
 * constructor among them, and that fails at runtime only. So the tests are compiled again the
 * way a consumer's code would be, against the oldest Ktor 3 the adapter promises, and run
 * against the adapter as it is published.
 */
val ktorOldestVersion = libs.versions.ktorOldest.get()

val oldestKtorTest: SourceSet = sourceSets.create("oldestKtorTest") {
    kotlin.srcDir("src/test/kotlin")
    // HttpRequestLifecycle came in Ktor 3.4.0.
    kotlin.exclude("**/RequestLifecycleTest.kt")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations.named("oldestKtorTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("oldestKtorTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
listOf("oldestKtorTestCompileClasspath", "oldestKtorTestRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        resolutionStrategy.eachDependency { if (requested.group == "io.ktor") useVersion(ktorOldestVersion) }
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
}

tasks.check { dependsOn(testOldestKtor) }
