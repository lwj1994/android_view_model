plugins {
    id("com.android.library") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.10" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}

subprojects {
    tasks.register("prepareKotlinBuildScriptModel") {
        dependsOn(rootProject.tasks.named("prepareKotlinBuildScriptModel"))
    }
}
