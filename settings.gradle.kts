pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "streamlord"

include(
    "streamlord-core",
    "streamlord-html",
    "streamlord-json-kotlinx",
    "streamlord-json-jackson",
    "streamlord-ktor",
    "streamlord-spring",
)
