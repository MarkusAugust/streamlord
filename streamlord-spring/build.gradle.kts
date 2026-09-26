description = "Streamlord Spring adapter: the Shield. Spring and the servlet API are compileOnly; bring your own."

dependencies {
    api(project(":streamlord-core"))
    compileOnly(libs.spring.web)
    compileOnly(libs.spring.webmvc)
    compileOnly(libs.jakarta.servlet)

    testImplementation(kotlin("test"))
    testImplementation(libs.spring.web)
    testImplementation(libs.spring.webmvc)
    testImplementation(libs.spring.test)
    testImplementation(libs.jakarta.servlet)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":streamlord-json-jackson"))
    testImplementation(libs.jackson.kotlin)
}
