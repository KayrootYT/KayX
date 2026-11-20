pluginManagement {
    repositories {
        google()
        // Google-hosted Maven Central mirror provides a resilient fallback when
        // repo.maven.apache.org is unavailable to GitHub-hosted runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}
rootProject.name = "KayX"
include(":app")
