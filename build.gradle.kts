import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/*
 * The forge that shapes every module of Streamlord.
 *
 * Every subproject is a Kotlin/JVM library built for Java 17 (the oldest realm
 * both Ktor 3 and Spring Framework 7 still tolerate), compiled in explicit API
 * mode so that nothing leaves the walls of a module by accident.
 */
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        explicitApi()
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
            freeCompilerArgs.addAll("-Xjdk-release=17", "-Xjsr305=strict")
        }
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Streamlord: a Kotlin SDK for Datastar. Bend server streams to your will.")
                    url.set("https://github.com/MarkusAugust/streamlord")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    scm {
                        url.set("https://github.com/MarkusAugust/streamlord")
                        connection.set("scm:git:https://github.com/MarkusAugust/streamlord.git")
                    }
                }
            }
        }
    }
}
