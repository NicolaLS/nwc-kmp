import com.vanniktech.maven.publish.MavenPublishBaseExtension

val groupId = "io.github.nicolals"
val versionName = "0.2.0-SNAPSHOT"

plugins {
    id("com.android.library") version "8.12.3" apply false
    id("com.android.kotlin.multiplatform.library") version "8.12.3" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.20" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
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

            if (rootProject.hasProperty("signing.keyId")) {
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
