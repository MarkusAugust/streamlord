description = "Streamlord JSON adapter for Jackson 3 (tools.jackson)."

dependencies {
    api(project(":streamlord-core"))
    api(libs.jackson.databind)

    testImplementation(kotlin("test"))
    testImplementation(libs.jackson.kotlin)
}
