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
    withSourcesJar()
    withJavadocJar()
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
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // JDBC backend — optional, activated when a DataSource bean is present.
    compileOnly("org.springframework:spring-jdbc")

    // Redis backend — optional, activated when RedisConnectionFactory is
    // present. Lettuce is the default Spring Boot Redis client.
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")

    // Generates additional-spring-configuration-metadata.json so IDEs
    // (IntelliJ, VS Code) can autocomplete our properties in application.yml.
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // === Test scope ===
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "ch.qos.logback") // we use logback-classic via spring-boot-starter
    }
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("com.h2database:h2") // in-memory JDBC for unit IT
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    // POM metadata is read from gradle.properties (POM_NAME, POM_DESCRIPTION, ...).
}
