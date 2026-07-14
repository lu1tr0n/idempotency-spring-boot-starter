// build.gradle.kts
//
// Spring Boot starter (not a Spring Boot application). The published artifact
// is a plain library JAR; there is no boot-jar. Spring's dependency versions
// come from the BOM imported below via the io.spring.dependency-management
// plugin — the org.springframework.boot Gradle plugin is deliberately NOT
// applied, since none of its tasks (bootJar/bootRun) are used here.

import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

java {
    toolchain {
        // Java 21 matches the Spring Boot 3.5 baseline and what
        // spring-idempotency-kit uses. Consumers on Java 17 can still
        // depend on us because we compile to bytecode 17 below.
        languageVersion = JavaLanguageVersion.of(21)
    }
    // Compile to bytecode 17 so apps on Java 17 LTS still pick us up.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Note: sources jar + javadoc jar are added to the publication by the
    // Vanniktech maven-publish plugin (see mavenPublishing block below).
    // Calling `withSourcesJar()` / `withJavadocJar()` here would duplicate
    // them and Gradle rejects the publish with "multiple artifacts with the
    // identical extension and classifier".
}

// Default (3.5.16) lives in gradle.properties so Dependabot can bump it; CI
// overrides it with `-PspringBootBomVersion=3.5.0` to prove the source still
// compiles and tests green against the floor of our advertised support range.
// If the source only uses symbols present in the floor BOM, the shipped
// bytecode references those same symbols and stays compatible across 3.5.x.
val springBootBomVersion: String by project

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootBomVersion")
    }
}

dependencies {
    // Core — needed at runtime for the auto-config + properties metadata.
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Servlet filter — required only when Spring Web is on the consumer's
    // classpath. `compileOnly` lets non-web apps depend on this starter
    // without dragging in Tomcat.
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // WebFlux filter — optional, activated when spring-webflux is on the
    // consumer's classpath. compileOnly so servlet-only apps don't drag in
    // reactor-netty.
    compileOnly("org.springframework:spring-webflux")
    compileOnly("io.projectreactor:reactor-core")

    // @Idempotent annotation AOP advisor — optional, activated when
    // spring-aop is on the classpath (transitive via spring-boot-starter-aop
    // when the consumer pulls it in).
    compileOnly("org.springframework:spring-aop")
    compileOnly("org.aspectj:aspectjweaver")
    // Jackson is used by the annotation aspect to serialise method return
    // values into the store. compileOnly — every Spring Boot web app already
    // has it via spring-boot-starter-json.
    compileOnly("com.fasterxml.jackson.core:jackson-databind")

    // JDBC backend — optional, activated when a DataSource bean is present.
    compileOnly("org.springframework:spring-jdbc")

    // Redis backend — optional, activated when RedisConnectionFactory is
    // present. Lettuce is the default Spring Boot Redis client.
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")

    // Principal binding — optional, activated when Spring Security is on the
    // consumer's classpath. compileOnly so apps that don't bind the key to a
    // principal (or supply their own IdempotencyPrincipalResolver) don't drag
    // in Spring Security. spring-security-core carries both SecurityContextHolder
    // (servlet) and ReactiveSecurityContextHolder (WebFlux).
    compileOnly("org.springframework.security:spring-security-core")

    // Tracing / metrics — optional. We instrument via the Micrometer
    // Observation API, which bridges to BOTH OpenTelemetry and Brave through
    // micrometer-tracing. compileOnly + an ObservationRegistry.NOOP fallback so
    // an app with no Actuator / tracer is completely unaffected. (In practice
    // micrometer-observation is already a transitive of spring-web, but we
    // declare it first-class to make the dependency intentional.)
    compileOnly("io.micrometer:micrometer-observation")

    // Actuator health indicator — optional. spring-boot-actuator carries
    // HealthIndicator/Health; spring-boot-actuator-autoconfigure carries
    // @ConditionalOnEnabledHealthIndicator. compileOnly + @ConditionalOnClass
    // gating so an app without Actuator is completely unaffected (nothing in
    // core/store references Actuator; only the guarded nested config + the
    // indicator do).
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")

    // Optional L1 cache — Caffeine for the in-process cache, micrometer-core for
    // the optional CaffeineCacheMetrics binder. compileOnly + @ConditionalOnClass
    // + explicit spring.idempotency.cache.enabled opt-in: an app without Caffeine
    // (or that leaves the cache off) is completely unaffected, and Caffeine being
    // a common transitive dependency never silently wraps the store.
    compileOnly("com.github.ben-manes.caffeine:caffeine")
    compileOnly("io.micrometer:micrometer-core")

    // Generates additional-spring-configuration-metadata.json so IDEs
    // (IntelliJ, VS Code) can autocomplete our properties in application.yml.
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // === Test scope ===
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback") // we use logback-classic via spring-boot-starter
    }
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-aop")
    testImplementation("org.springframework.boot:spring-boot-starter-security") // principal-binding tests (servlet + webflux)
    testImplementation("org.springframework.security:spring-security-test")     // mockUser / SecurityMockServerConfigurers
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("io.micrometer:micrometer-observation-test") // TestObservationRegistry
    testImplementation("com.github.ben-manes.caffeine:caffeine")    // L1 cache decorator tests
    testImplementation("org.springframework.boot:spring-boot-starter-actuator") // health-indicator integration tests
    testImplementation("org.flywaydb:flyway-core")                  // apply the shipped Flyway script in tests
    testImplementation("org.flywaydb:flyway-database-postgresql")   // Flyway 10+ Postgres support module
    testImplementation("org.liquibase:liquibase-core")              // apply the shipped Liquibase changelog in tests
    testImplementation("com.h2database:h2") // in-memory JDBC for unit IT
    testImplementation("org.postgresql:postgresql") // real driver for Testcontainers Postgres IT
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    // Embed parameter names so SpEL expressions like `#request.orderId` in
    // @Idempotent can resolve method args by name (Spring's
    // DefaultParameterNameDiscoverer reads this metadata first).
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Surface stdout/stderr from container-backed tests so CI logs are useful.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// The Spring `io.spring.dependency-management` plugin resolves dependency
// versions from a BOM at compile / runtime, but does not write those
// versions into the published Gradle module metadata or POM. Gradle's
// validator then rejects the publish with "dependencies without versions".
//
// Suppressing the check is the standard fix when publishing a library
// whose downstream consumers also pull in the Spring BOM (which is true
// for any Spring Boot starter). Documented in
// https://docs.gradle.org/current/userguide/publishing_maven.html#publishing_maven:resolved_dependencies
tasks.withType<GenerateModuleMetadata> {
    suppressedValidationErrors.add("dependencies-without-versions")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    // POM metadata is read from gradle.properties (POM_NAME, POM_DESCRIPTION, ...).
}

// Secondary publish target: GitHub Packages. This is a mirror — Maven Central
// remains the canonical registry that real consumers depend on (it has no
// auth requirement for reads). GitHub Packages requires a PAT even for
// public packages, so very few consumers will pull from here in practice.
// The point of mirroring is the repo page sidebar ("Packages" section) and
// having a backup registry in case Sonatype is ever unreachable.
//
// Credentials come from env vars set by the release workflow:
//   GITHUB_ACTOR — the GH user that triggered the workflow run
//   GITHUB_TOKEN — the auto-provisioned token with `packages: write`
// When those env vars are missing (e.g. running locally without auth), the
// repository definition is harmless; only `publishAllPublicationsToGitHubPackagesRepository`
// would fail at the upload step, not the build.
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/lu1tr0n/idempotency-spring-boot-starter")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
