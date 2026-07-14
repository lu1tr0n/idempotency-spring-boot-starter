// settings.gradle.kts
//
// The root project is the Spring Boot starter (all the engine, filters, AOP,
// and the jdbc/redis/cache backends). The `idempotency-core` sub-module holds
// only the frozen, dependency-free storage SPI so third parties can ship a
// backend by depending on core alone. The starter depends on core via `api`,
// so existing consumers get the identical class set and behavior.

rootProject.name = "idempotency-spring-boot-starter"

include("idempotency-core")

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
