description = "Streamlord HTML: kotlinx.html DSL with Datastar attributes, actions and expressions."

dependencies {
    api(project(":streamlord-core"))
    api(libs.kotlinx.html)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
