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
    id("com.vanniktech.maven.publish") version "0.30.0"
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

// No dependencies on purpose. See the package javadoc in
// io.github.lu1tr0n.idempotency.core for the stability + purity contract.

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
