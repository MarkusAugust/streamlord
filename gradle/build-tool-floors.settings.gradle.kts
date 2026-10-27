/*
 * Floors for what the build's own tools pull in: Dokka brings Jackson, jsoup and FreeMarker into
 * the plugin classpath and its generator classpath, and the IntelliJ Platform plugin brings
 * Jackson and jsoup. Their pins carry published advisories. Nothing here reaches a published
 * artifact; it only raises what the build runs on, and only raises: a tool that already asks for
 * something newer keeps it.
 *
 * The versions are in gradle/libs.versions.toml, under buildTools*. Applied from both
 * settings.gradle.kts and editors/intellij/settings.gradle.kts.
 */
gradle.allprojects {
    // Read when a configuration resolves: the catalog is not registered yet when this runs.
    val project = this
    fun floor(key: String): String = project.extensions.getByType<VersionCatalogsExtension>()
        .named("libs").findVersion(key).get().requiredVersion

    fun isOlder(requested: String, floor: String): Boolean {
        val a = requested.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = floor.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    val raise = Action<DependencyResolveDetails> {
        val version = requested.version ?: return@Action
        val target = when {
            // Since 2.20, jackson-annotations is versioned by minor alone ("2.22").
            requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations" ->
                floor("buildToolsJackson").substringBeforeLast('.')
            requested.group.startsWith("com.fasterxml.jackson") -> floor("buildToolsJackson")
            requested.group == "org.jsoup" && requested.name == "jsoup" -> floor("buildToolsJsoup")
            requested.group == "org.freemarker" && requested.name == "freemarker" -> floor("buildToolsFreemarker")
            else -> return@Action
        }
        if (isOlder(version, target)) {
            useVersion(target)
            because("raised past advisories in the version a build tool pins")
        }
    }

    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(raise) }
    configurations.matching { it.name.startsWith("dokka") }.configureEach {
        resolutionStrategy.eachDependency(raise)
    }
    // The IntelliJ Platform test framework pins Jackson 2.19.0. Only test configurations, so what
    // the plugin bundles is left as it is.
    pluginManager.withPlugin("org.jetbrains.intellij.platform") {
        configurations.matching { it.name.contains("test", ignoreCase = true) }.configureEach {
            resolutionStrategy.eachDependency(raise)
        }
    }
}
