pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android_view_model"
include(":android-view-model")
include(":example")

sourceControl {
    gitRepository(uri("https://github.com/lwj1994/android_view_model.git")) {
        producesModule("android_view_model:android-view-model")
    }
}
