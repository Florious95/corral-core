pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("goldenCoreRepo").get()) }
        mavenCentral()
    }
}
rootProject.name = "snapshot-core-build"
include(":core-conn", ":core-protocol")
project(":core-conn").projectDir = file("../../app/core-conn")
