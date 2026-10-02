description =
    "Streamlord test assertions: read a Datastar response back into events and assert on what " +
        "it meant. Binds no test framework, so it works under kotlin.test, JUnit, Kotest or TestNG alike."

dependencies {
    api(project(":streamlord-core"))

    testImplementation(kotlin("test"))
}
