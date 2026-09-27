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
    // The SDK's version catalog, so the Kotlin Gradle plugin is the same version in both builds:
    // a composite build needs one classloader for it.
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "streamlord-intellij"

// The SDK build, for `streamlord-analysis` (and `streamlord-core` beneath it), so the plugin
// judges Datastar strings with the same code as the SDK's own tests.
includeBuild("../..")
