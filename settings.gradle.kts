pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal() // Local snapshots take precedence for development
        google()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

rootProject.name = "nwc-kmp"
include(":nwc-kmp")

// Include nostr-kmp for local development
includeBuild("../nostr-kmp") {
    dependencySubstitution {
        substitute(module("io.github.nicolals:nostr-core")).using(project(":nostr-core"))
        substitute(module("io.github.nicolals:nostr-codec-kotlinx-serialization")).using(project(":nostr-codec-kotlinx"))
        substitute(module("io.github.nicolals:nostr-crypto")).using(project(":nostr-crypto"))
        substitute(module("io.github.nicolals:nostr-runtime-coroutines")).using(project(":nostr-runtime-coroutines"))
        substitute(module("io.github.nicolals:nostr-transport-ktor")).using(project(":nostr-transport-ktor"))
        substitute(module("io.github.nicolals:nostr-nip04")).using(project(":nips:nip04"))
        substitute(module("io.github.nicolals:nostr-nip42")).using(project(":nips:nip42"))
        substitute(module("io.github.nicolals:nostr-nip44")).using(project(":nips:nip44"))
        substitute(module("io.github.nicolals:nostr-nip47")).using(project(":nips:nip47"))
    }
}
