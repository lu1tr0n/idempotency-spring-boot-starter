// settings.gradle.kts
//
// Single-module Gradle project. The artifact ships as one JAR; backends
// (JDBC, Redis) are auto-activated via Spring Boot conditionals based on
// classpath + bean presence, not via separate Maven modules.

rootProject.name = "idempotency-spring-boot-starter"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
