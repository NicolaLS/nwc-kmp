import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}


kotlin {
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    android {
        namespace = "io.github.nicolals.nwc"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
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
                // nostr-core types (PublicKey) are exposed in public API
                api(libs.nostr.core)
                implementation(libs.nostr.codec.kotlinx.serialization)
                implementation(libs.nostr.runtime.coroutines)
                implementation(libs.nostr.transport.ktor)
                implementation(libs.nostr.crypto)
                implementation(libs.nip44)
                implementation(libs.nip04)
                // NIP-47 types (NwcEncryption) are exposed in public API
                api(libs.nip47)
                implementation(libs.ktor.client.core)
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
