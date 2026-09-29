import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}

/*
 * The forge that shapes every module of Streamlord.
 *
 * Every subproject is a Kotlin/JVM library built for Java 17 (the oldest realm
 * both Ktor 3 and Spring Framework 7 still tolerate), compiled in explicit API
 * mode so that nothing leaves the walls of a module by accident.
 */
subprojects {
    /*
     * Modules that exist for the build itself and never leave it. They are not
     * published, they are not held to explicit API mode, and an unused value in
     * a documentation sample is not worth failing a release over.
     */
    val internal = name in setOf("docs-samples", "demo")

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    if (!internal) apply(plugin = "com.vanniktech.maven.publish")
    if (!internal) apply(plugin = "org.jetbrains.dokka")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        if (!internal) explicitApi()
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(!internal)
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

    if (internal) return@subprojects

    /*
     * Maven Central, through Sonatype's Central Portal. Credentials and the signing key come
     * from CI secrets as Gradle properties (ORG_GRADLE_PROJECT_mavenCentralUsername etc.);
     * without them the build still runs, only publishing does not.
     */
    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
        /*
         * A real API reference in the javadoc jar, not an empty one.
         *
         * Every module carried JavadocJar.Empty() until now, which meant javadoc.io showed a
         * blank page for a library whose source holds 445 KDoc blocks. IntelliJ users never
         * noticed, because the sources jar is published too and the IDE reads the comments from
         * there; everyone else got nothing.
         *
         * The task name is Dokka 2's. `dokkaHtml` is a v1 task and the plugin now reports it as
         * disabled, which is the sort of thing that builds a jar with nothing in it and says
         * BUILD SUCCESSFUL — so it was read from `gradlew tasks`, not from a guide.
         */
        configure(
            KotlinJvm(
                javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                sourcesJar = true,
            ),
        )
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
