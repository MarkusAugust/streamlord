import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}

// The oldest Kotlin a consumer may compile with; see compilerOptions below.
val kotlinFloor: String = libs.versions.kotlinFloor.get()

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

    // MIT asks that the notice and the licence text travel with every copy, and a jar is a copy.
    tasks.withType<Jar>().configureEach {
        from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
    }
    // A stable module name for a consumer on the module path, so the jar does not take its file name.
    tasks.withType<Jar>().matching { it.name == "jar" }.configureEach {
        manifest.attributes(
            "Automatic-Module-Name" to "io.github.markusaugust.streamlord." + project.name.removePrefix("streamlord-").replace('-', '.'),
        )
    }
    version = rootProject.version

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        /*
         * Published modules are held to explicit API mode, which makes every public declaration
         * a decision, and to the dump in api/<module>.api, which makes every change to one visible.
         *
         * A default parameter added to a public function compiles for every caller and still
         * removes the JVM signature that code compiled against the previous version calls, which
         * surfaces as NoSuchMethodError at runtime and never at compile time. `check` compares
         * the dump with the code and turns that into a red build; after an intended change,
         * `./gradlew updateKotlinAbi` rewrites the dump, and the diff is reviewed with the rest.
         */
        if (!internal) {
            explicitApi()
            @OptIn(ExperimentalAbiValidation::class)
            abiValidation()
        }
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(!internal)
            freeCompilerArgs.addAll("-Xjdk-release=17", "-Xjsr305=strict")
            /*
             * A Kotlin compiler reads metadata at most one version ahead of its own, so modules
             * built by this Kotlin at its own level would refuse to compile in a project on the
             * Kotlin Spring Boot 4.0 manages. Built to that level instead, and with that standard
             * library in the POM; docs-samples' checkKotlinFloor compiles against them with it.
             */
            if (!internal) {
                val floor = KotlinVersion.fromVersion(kotlinFloor.substringBeforeLast('.'))
                languageVersion.set(floor)
                apiVersion.set(floor)
                // Where an annotation on a constructor property lands by default from Kotlin 2.4,
                // the parameter and the field, as the modules built at 2.4 had it.
                freeCompilerArgs.add("-Xannotation-default-target=param-property")
            }
        }
        if (!internal) coreLibrariesVersion = kotlinFloor
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

    // The javadoc jar is the publish plugin's own task, which the Jar rule above does not reach.
    tasks.withType<com.vanniktech.maven.publish.tasks.JavadocJar>().configureEach {
        from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
    }

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
         * BUILD SUCCESSFUL, so it was read from `gradlew tasks`, not from a guide.
         */
        configure(
            KotlinJvm(
                javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                sourcesJar = SourcesJar.Sources(),
            ),
        )
        coordinates(rootProject.group.toString(), project.name, rootProject.version.toString())
        pom {
            name.set(project.name)
            // Every published module sets its own, so this only catches a new one that forgot.
            description.set(project.provider { project.description ?: "Streamlord: a Datastar toolchain for Kotlin." })
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
