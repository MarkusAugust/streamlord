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
    "demo",
    "docs-samples",
    "streamlord-core",
    "streamlord-analysis",
    "streamlord-html",
    "streamlord-html-pro",
    "streamlord-json-kotlinx",
    "streamlord-json-jackson",
    "streamlord-json-jackson2",
    "streamlord-ktor",
    "streamlord-spring",
    "streamlord-test",
)
