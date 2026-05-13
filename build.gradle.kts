import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

val groupId = "io.github.nicolals"
val versionName = "0.3.2-SNAPSHOT"

plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

allprojects {
    group = groupId
    version = versionName
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension>("mavenPublishing") {
            publishToMavenCentral()

            pom {
                name.set("Nostr Wallet Connect KMP")
                description.set("Kotlin Multiplatform client for Nostr Wallet Connect (NIP-47)")
                url.set("https://github.com/nicolals/nwc-kmp")
                inceptionYear.set("2025")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }

                scm {
                    url.set("https://github.com/nicolals/nwc-kmp")
                    connection.set("scm:git:git://github.com/nicolals/nwc-kmp.git")
                    developerConnection.set("scm:git:ssh://git@github.com/nicolals/nwc-kmp.git")
                }

                developers {
                    developer {
                        id.set("nicolals")
                        name.set("NicolaLS")
                        url.set("https://github.com/nicolals")
                    }
                }
            }

            if (
                rootProject.hasProperty("signing.keyId") ||
                rootProject.hasProperty("signingInMemoryKey") ||
                rootProject.hasProperty("signingInMemoryKeyId")
            ) {
                signAllPublications()
            }
        }
    }
}

if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel") {
        group = "help"
        description = "No-op to satisfy older IDEs that request this removed Gradle task."
    }
}

gradle.projectsEvaluated {
    // Keep compile/package work parallel and allow each module to upload its own
    // publications together, but serialize uploads between modules to reduce
    // Central Portal rate-limit pressure.
    val mavenCentralUploadTasksByProject = allprojects
        .map { project ->
            project to project.tasks.withType(PublishToMavenRepository::class.java)
                .matching { it.repository.name == "mavenCentral" }
                .toList()
        }
        .filter { (_, tasks) -> tasks.isNotEmpty() }
        .sortedBy { (project, _) -> project.path }

    mavenCentralUploadTasksByProject.zipWithNext().forEach { taskGroups ->
        val previousTasks = taskGroups.first.second
        val nextTasks = taskGroups.second.second

        nextTasks.forEach { next ->
            previousTasks.forEach { previous ->
                next.mustRunAfter(previous)
            }
        }
    }
}
