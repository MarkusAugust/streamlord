description = "Streamlord core: the Datastar protocol, pure and without allegiance to any framework."

dependencies {
    api(libs.kotlinx.coroutines.core)

    // @Language on the HTML, JSON and JavaScript parameters, for IntelliJ. compileOnly: it never
    // reaches a consumer from here, and kotlin-stdlib ships the same artifact anyway.
    compileOnly(libs.jetbrains.annotations)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
