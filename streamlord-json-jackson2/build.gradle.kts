description = "Streamlord JSON adapter for Jackson 2 (com.fasterxml)."

dependencies {
    api(project(":streamlord-core"))
    api(libs.jackson2.databind)
    // Read only beside jackson-module-kotlin, which depends on it.
    compileOnly(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation(libs.jackson2.kotlin)
}
