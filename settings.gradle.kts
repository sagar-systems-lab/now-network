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

rootProject.name = "NOWNetwork"

include(":app")
project(":app").projectDir = file("apps/android")

include(":core:designsystem")
project(":core:designsystem").projectDir = file("core/designsystem")

include(":benchmark")
project(":benchmark").projectDir = file("benchmark")
