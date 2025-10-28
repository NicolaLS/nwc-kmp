plugins {
    id("com.android.library") version "8.12.3" apply false
    id("com.android.kotlin.multiplatform.library") version "8.12.3" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.20" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel") {
        group = "help"
        description = "No-op to satisfy older IDEs that request this removed Gradle task."
    }
}
