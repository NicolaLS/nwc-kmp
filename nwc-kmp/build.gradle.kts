import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = property("GROUP") as String
version = property("VERSION_NAME") as String


kotlin {
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    androidTarget()
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget> {
        binaries.framework {
            baseName = "NwcKmp"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.nostr.core)
                implementation(libs.nostr.codec.kotlinx.serialization)
                implementation(libs.nostr.runtime.coroutines)
                implementation(libs.nostr.transport.ktor)
                implementation(libs.nostr.crypto)
                implementation(libs.nip44)
                implementation(libs.nip04)
                implementation(libs.nip42)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core.v331)
                implementation(libs.ktor.client.websockets.v331)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }

        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

android {
    namespace = "io.github.nostr.nwc"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    pom {
        name = property("POM_NAME") as String
        description = property("POM_DESCRIPTION") as String
        inceptionYear = "2025"
        url = property("POM_URL") as String
        licenses {
            license {
                name = property("POM_LICENSE_NAME") as String
                url = property("POM_LICENSE_URL") as String
                distribution = property("POM_LICENSE_DIST") as String
            }
        }
        developers {
            developer {
                id = property("POM_DEVELOPER_ID") as String
                name = property("POM_DEVELOPER_NAME") as String
                url = property("POM_DEVELOPER_URL") as String
            }
        }
        scm {
            val repoUrl = property("POM_URL") as String
            url = repoUrl
            connection = "scm:git:$repoUrl.git"
            developerConnection = "scm:git:$repoUrl.git"
        }
    }
}
