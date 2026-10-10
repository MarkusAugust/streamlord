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
    testImplementation(project(":streamlord-test"))
}
