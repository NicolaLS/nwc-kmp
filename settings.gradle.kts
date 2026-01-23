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
        mavenLocal() // Local snapshots take precedence for development
        google()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

rootProject.name = "nwc-kmp"
include(":nwc-kmp")
