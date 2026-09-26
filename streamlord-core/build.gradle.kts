description = "Streamlord core: the Datastar protocol, pure and without allegiance to any framework."

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
