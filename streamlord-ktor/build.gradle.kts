plugins {
    alias(libs.plugins.kotlin.serialization)
}

description = "Streamlord Ktor adapter: the Sword. Brings nothing you do not already carry; Ktor is compileOnly."

dependencies {
    api(project(":streamlord-core"))
    compileOnly(libs.ktor.server.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":streamlord-json-kotlinx"))
    testImplementation(project(":streamlord-html"))
}
