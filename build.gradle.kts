import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
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
    apply(plugin = "com.vanniktech.maven.publish")

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

    /*
     * Maven Central, through Sonatype's Central Portal. Credentials and the signing key come
     * from CI secrets as Gradle properties (ORG_GRADLE_PROJECT_mavenCentralUsername etc.);
     * without them the build still runs, only publishing does not.
     */
    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
        configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
        coordinates(rootProject.group.toString(), project.name, rootProject.version.toString())
        pom {
            name.set(project.name)
            description.set(project.provider { project.description ?: "Streamlord: a Kotlin SDK for Datastar." })
            url.set("https://github.com/MarkusAugust/streamlord")
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/licenses/MIT")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("MarkusAugust")
                    name.set("Markus August")
                    url.set("https://github.com/MarkusAugust")
                }
            }
            scm {
                url.set("https://github.com/MarkusAugust/streamlord")
                connection.set("scm:git:https://github.com/MarkusAugust/streamlord.git")
                developerConnection.set("scm:git:git@github.com:MarkusAugust/streamlord.git")
            }
        }
    }
}
