import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}


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
