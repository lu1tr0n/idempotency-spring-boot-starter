// idempotency-core
//
// The frozen, dependency-free storage SPI. Third parties depend on ONLY this
// module to ship a backend (implement IdempotencyStore, register a bean) — no
// Spring, no servlet, no web stack. The absence of any compile dependency here
// is deliberate and load-bearing: it is what keeps the `core` package pure, so
// do not add framework dependencies to this module.

import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    // Ships the store contract TCK as a published `-test-fixtures` artifact so
    // backend authors can validate their IdempotencyStore against the same
    // contract the built-in stores meet. Test fixtures are a separate source
    // set + artifact, so the MAIN jar stays dependency-free.
    `java-test-fixtures`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    // Compile to bytecode 17 so apps on Java 17 LTS still pick us up — same
    // baseline as the starter.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The MAIN source set has NO dependencies on purpose. See the package javadoc
// in io.github.lu1tr0n.idempotency.core for the stability + purity contract.
// JUnit only enters via the test-fixtures and test source sets below, never the
// published main jar. Versions match the Spring Boot 3.5 BOM the starter uses.
dependencies {
    // The TCK abstract test lives in test fixtures and needs JUnit at compile
    // time; `testFixturesApi` so consumers of the fixtures inherit it.
    testFixturesApi(platform("org.junit:junit-bom:5.12.2"))
    testFixturesApi("org.junit.jupiter:junit-jupiter")

    // Core's own unit tests (IdempotencyKey, ResponseTtlDirective) use AssertJ;
    // they also run the TCK against the in-memory store (via the implicit
    // test -> testFixtures dependency the plugin wires up).
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    // Coordinates/POM read from this module's gradle.properties (POM_ARTIFACT_ID,
    // POM_NAME, POM_DESCRIPTION); GROUP + VERSION_NAME inherit from the root.
}

// Secondary mirror: GitHub Packages. Mirrors the starter's setup; only used by
// the release workflow when GITHUB_ACTOR/GITHUB_TOKEN are present.
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
