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
        // Onyx onyxsdk-device 官方 repo，不在 Maven Central 上
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "BooxDashboard"
include(":app")
