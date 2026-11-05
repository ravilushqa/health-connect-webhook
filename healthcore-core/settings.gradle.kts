// Lets this module build on its own (`./gradlew -p healthcore-core test`), e.g. where the
// Android Gradle plugin cannot be downloaded. In the full build this file is ignored and
// the module is :healthcore-core of the root project.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "healthcore-core"
