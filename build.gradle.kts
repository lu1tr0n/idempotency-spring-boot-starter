// build.gradle.kts
//
// Spring Boot starter (not a Spring Boot application) — Boot's plugin is
// applied for dependency management only; the executable boot-jar task is
// disabled so the published artifact is a plain library JAR.

import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    id("org.springframework.boot") version "3.4.4" apply false
    id("io.spring.dependency-management") version "1.1.7"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

java {
    toolchain {
        // Java 21 matches the Spring Boot 3.4 baseline and what
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.4")
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
