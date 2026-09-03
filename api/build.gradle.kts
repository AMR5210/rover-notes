plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.rovernotes"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Spring Boot 4.1 does not manage the Modulith version, so it is pinned here.
// Modulith 2.1.x is the line that targets Boot 4.x / Spring Framework 7.
extra["springModulithVersion"] = "2.1.0"
extra["springAiVersion"] = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // --- web + api ------------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring Data JDBC, deliberately not JPA. Retrieval is hand-written SQL and
    // Hibernate's session/lazy-loading model adds complexity with no upside here.
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")

    // --- auth -----------------------------------------------------------------
    // This service both issues and validates its own JWTs. See docs/ARCHITECTURE.md, which
    // supersedes docs/ARCHITECTURE.md.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // The OAuth2 and OIDC protocol comes from Spring Authorization Server rather than
    // being written here: authorization code with PKCE, issuance, refresh, JWKS, and
    // discovery metadata. Boot manages the version, which tracks Spring Security's.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    // Argon2PasswordEncoder calls BouncyCastle's Argon2 implementation directly and
    // spring-security-crypto does not declare it, so it has to be brought in here.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    // Verification and password reset are only as trustworthy as their delivery, so mail
    // is a dependency of the identity flows rather than a detail of them.
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // --- modularity -----------------------------------------------------------
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // JDBC event publication registry — the durable outbox, for free. See docs/ARCHITECTURE.md.
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")

    // --- object storage -------------------------------------------------------
    // The AWS SDK rather than MinIO's own client: MinIO speaks the S3 API, and this is
    // the same dependency a deployment against S3 would use, so the only difference
    // between the two is an endpoint. The URL-connection HTTP client keeps the
    // transitive weight down — Netty and Apache both arrive by default otherwise, and
    // uploads here are occasional rather than a throughput path.
    implementation("software.amazon.awssdk:s3:2.54.2") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:url-connection-client:2.54.2")

    // Azure Blob is not S3-compatible, so the Azure deployment needs its own client.
    // azure-identity supplies DefaultAzureCredential, which resolves the container app's
    // managed identity and keeps the storage key out of configuration entirely.
    implementation("com.azure:azure-storage-blob:12.35.1")
    implementation("com.azure:azure-identity:1.18.5")

    // --- persistence ----------------------------------------------------------
    // pgvector's PGvector type binds directly as a JdbcTemplate/JdbcClient parameter,
    // which is what lets the retrieval SQL stay hand-written. Verified against
    // pgvector-java 0.1.6.
    implementation("com.pgvector:pgvector:0.1.6")
    // Boot 4 ships each auto-configuration as its own module. `spring-boot-flyway`
    // carries the Flyway auto-configuration that runs migrations at startup; without
    // it, flyway-core is on the classpath but never invoked.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- llm ------------------------------------------------------------------
    // Spring AI is used for the model client, tool calling, and (from Week 6) the MCP
    // server. Retrieval orchestration stays hand-written — see docs/ARCHITECTURE.md.
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    // MCP server over Spring MVC. The webmvc starter carries the streamable-HTTP
    // transport; the plain starter is stdio only, which cannot serve a deployed process.
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // --- observability --------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // --- test -----------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Testcontainers 2.x renamed every module to a testcontainers-* prefix.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

// Benchmarks are excluded from `test` rather than deleted. A figure quoted in the
// documentation has to be reproducible by whoever reads it, and one measured with a
// throwaway probe is not — but a benchmark inside the suite is thousands of database round
// trips on every build for a number no assertion depends on.
//
// Configured on `test` by name rather than through `withType<Test>`, because that would
// apply the exclusion to the benchmark task as well and leave it including and excluding
// the same tag, which runs nothing and reports success.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("benchmark") }
}

/** The benchmarks behind the figures in docs/, run on demand: `./gradlew benchmark`. */
tasks.register<Test>("benchmark") {
    group = "verification"
    description = "Runs the benchmarks behind the figures in docs/, excluded from `test`"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.withType<JavaCompile> {
    // -parameters keeps constructor parameter names for Spring Data JDBC mapping.
    // -Xlint:deprecation surfaces API removals early rather than at upgrade time.
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}
