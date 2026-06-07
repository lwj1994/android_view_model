plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
    id("signing")
}

group = "io.github.lwj1994"
version = "0.1.1"

val isJitPack = providers.environmentVariable("JITPACK")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
val publishedGroupId = if (isJitPack) {
    "${providers.environmentVariable("GROUP").get()}.${providers.environmentVariable("ARTIFACT").get()}"
} else {
    "io.github.lwj1994"
}
val publishedVersion = if (isJitPack) {
    providers.environmentVariable("VERSION").get()
} else {
    "0.1.1"
}

val isPublishingToMavenCentral = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("MavenCentral", ignoreCase = true)
}

android {
    namespace = "milu.viewmodel"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("androidx.annotation:annotation:1.9.1")
    api("androidx.lifecycle:lifecycle-runtime:2.9.4")
    api("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    api("androidx.activity:activity:1.10.1")
    api("androidx.fragment:fragment:1.8.2")
    api("androidx.compose.runtime:runtime:1.9.4")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

mavenPublishing {
    publishToMavenCentral()
    if (isPublishingToMavenCentral) {
        signAllPublications()
    }

    coordinates(
        groupId = publishedGroupId,
        artifactId = "android-view-model",
        version = publishedVersion,
    )

    pom {
        name.set("AndroidViewModel")
        description.set("A small ViewModel registry and DI layer for Android.")
        inceptionYear.set("2026")
        url.set("https://github.com/lwj1994/android_view_model")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("lwj1994")
                name.set("wen")
                url.set("https://github.com/lwj1994")
            }
        }

        scm {
            url.set("https://github.com/lwj1994/android_view_model")
            connection.set("scm:git:git://github.com/lwj1994/android_view_model.git")
            developerConnection.set("scm:git:ssh://git@github.com/lwj1994/android_view_model.git")
        }
    }
}

signing {
    if (!providers.gradleProperty("signingInMemoryKey").isPresent) {
        useGpgCmd()
    }
}
