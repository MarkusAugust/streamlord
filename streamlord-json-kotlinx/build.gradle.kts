plugins {
    alias(libs.plugins.kotlin.serialization)
}

description = "Streamlord JSON adapter for kotlinx.serialization."

dependencies {
    api(project(":streamlord-core"))
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
