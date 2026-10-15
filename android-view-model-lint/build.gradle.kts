plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("com.android.tools.lint:lint-api:31.13.1")
    testImplementation(kotlin("stdlib"))
    testImplementation("com.android.tools.lint:lint-tests:31.13.1")
    testImplementation("com.android.tools.lint:lint-api:31.13.1")
    testImplementation("junit:junit:4.13.2")
}

tasks.jar {
    manifest.attributes["Lint-Registry-v2"] = "milu.viewmodel.lint.ViewModelIssueRegistry"
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
}
