description = "Streamlord JSON adapter for Jackson 2 (com.fasterxml)."

dependencies {
    api(project(":streamlord-core"))
    api(libs.jackson2.databind)

    testImplementation(kotlin("test"))
    testImplementation(libs.jackson2.kotlin)
}
