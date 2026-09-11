pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "torfilx"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:model")
include(":core:common")
include(":core:ui")
include(":core:data")
include(":core:player")
include(":core:torrent")
include(":core:testing")
include(":core:catalogue")
include(":core:catalogue-swarm")

include(":feature:home")
include(":feature:library")
include(":feature:details")
include(":feature:search")
include(":feature:player")
include(":feature:settings")

include(":tools:catalog-publisher")

