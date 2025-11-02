import org.gradle.api.JavaVersion
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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("io.github.nicolals:nostr-core:0.1.1-SNAPSHOT")
                implementation("io.github.nicolals:nostr-codec-kotlinx-serialization:0.1.1-SNAPSHOT")
                implementation("io.github.nicolals:nostr-runtime-coroutines:0.1.1-SNAPSHOT")
                implementation("io.github.nicolals:nostr-transport-ktor:0.1.1-SNAPSHOT")
                implementation("io.github.nicolals:nostr-crypto:0.1.1-SNAPSHOT")
                implementation("io.github.nicolals:nip44:0.1.1-SNAPSHOT")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("io.ktor:ktor-client-core:2.3.12")
                implementation("io.ktor:ktor-client-websockets:2.3.12")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
