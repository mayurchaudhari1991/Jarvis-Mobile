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

rootProject.name = "JarvisMobile"
include(
    ":app",
    ":core-device",
    ":core-device-android",
    ":core-model",
    ":core-inference",
    ":core-data",
    ":ui-hud"
)
