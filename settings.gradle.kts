pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "8.12.3"
        id("com.android.kotlin.multiplatform.library") version "8.12.3"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nwc-kmp"
include(":library")

listOf(
    ":nostr-core" to "../nostr-kmp/nostr-core",
    ":nostr-codec-kotlinx-serialization" to "../nostr-kmp/nostr-codec-kotlinx-serialization",
    ":nostr-runtime-coroutines" to "../nostr-kmp/nostr-runtime-coroutines",
    ":nostr-transport-ktor" to "../nostr-kmp/nostr-transport-ktor",
    ":nostr-crypto" to "../nostr-kmp/nostr-crypto",
    ":nips:nip44" to "../nostr-kmp/nips/nip44"
).forEach { (path, dir) ->
    include(path)
    project(path).projectDir = file(dir)
}
