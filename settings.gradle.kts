pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Permet à Gradle de provisionner le JDK 17 exigé par le module Cubism Framework.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoxGirlSupremacy"
include(":app")

// Module Cubism Framework du SDK Live2D (inclus depuis le dossier du SDK).
include(":cubismFramework")
project(":cubismFramework").projectDir =
    file("cubismFramework")
