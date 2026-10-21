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

rootProject.name = "SwipeToSender"

// The shared protocol module (shared/kotlin) is built from source; "dev.swipeto:protocol" resolves to it.
includeBuild("../../shared/kotlin")

include(":app")
