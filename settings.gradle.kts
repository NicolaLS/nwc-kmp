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
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenLocal()
    }
}

rootProject.name = "nwc-kmp"
include(":nwc-kmp")
