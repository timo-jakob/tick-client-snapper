plugins {
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    application
    id("com.google.protobuf") version "0.9.6"
    id("nebula.release") version "21.0.0"
    `jvm-test-suite`
    // Added by /development:bootstrap — CI + pre-commit prerequisites.
    id("com.diffplug.spotless") version "7.0.2"
    jacoco
    // SonarCloud analysis runs in-build via the Gradle plugin (`./gradlew sonar`)
    // so the scanner sees compiled classes + the JaCoCo report directly and
    // auto-configures binaries/sources/coverage. Replaces the standalone
    // scanner-CLI job that had no access to build/classes.
    id("org.sonarqube") version "7.3.1.8318"
}

repositories {
    mavenCentral()
}

group = "com.github.timojakob"

val grpcVersion = "1.78.0"
val protoVersion = "4.34.1"
val tomcatAnnotationsApiVersion = "6.0.53"

// Gradle dependency locking (S8569) — a committed gradle.lockfile pins the fully resolved
// dependency graph so builds are reproducible and versions are predictable. Renovate keeps the
// lockfile current automatically (`--update-locks` on regular updates, `--write-locks` during
// lock-file maintenance), so it stays maintainable under this repo's automated-update flow.
// Regenerate locally with `./gradlew dependencies --write-locks`.
//
// Dependency *verification* (gradle/verification-metadata.xml, S6474) — checksum/signature
// verification — is a separate, fail-closed mechanism that is NOT enabled here; that decision is
// deferred to human review (see the maintenance report). Supply-chain integrity is meanwhile
// enforced through SHA-pinned GitHub Actions (S7637), Snyk open-source scanning, Trivy FS
// scanning, and Dependabot alerts.
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // grpc (see https://github.com/grpc/grpc-java)
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")

    // Protobuf
    implementation("com.google.protobuf:protobuf-java:$protoVersion")
    implementation("com.google.protobuf:protobuf-java-util:$protoVersion")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // Compile-only
    compileOnly("org.apache.tomcat:annotations-api:$tomcatAnnotationsApiVersion") // necessary for Java 9+
}

// Since Spring Boot 4.1, the Boot Gradle plugin reacts to the com.google.protobuf
// plugin and configures the protobuf extension itself: it registers protoc
// (com.google.protobuf:protoc, version-aligned with protobuf-java on the runtime
// classpath -> $protoVersion) and the "grpc" codegen plugin
// (io.grpc:protoc-gen-grpc-java, version-aligned with io.grpc:grpc-util -> $grpcVersion),
// and wires the grpc plugin into all generate-proto tasks. The previous manual
// protobuf { protoc/plugins/generateProtoTasks } block duplicated exactly that and
// now fails ("ExecutableLocator with name 'grpc' already exists"), so it was removed.
// See: https://docs.spring.io/spring-boot/gradle-plugin/reacting.html

// NOTE: do NOT manually add the protobuf output dirs to the `main` source set.
// The com.google.protobuf plugin already registers its real output
// (build/generated/sources/proto/...) with the source set automatically. A
// manual srcDirs entry pointing at a stale/parallel path (e.g. the singular
// "source" dir) causes the same stubs to be compiled twice -> "duplicate class".

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }
    }
}

// --- added by /development:bootstrap ---------------------------------------
// Spotless (google-java-format) — formats Java; matches the pre-commit hook.
spotless {
    java {
        googleJavaFormat()
        // Generated protobuf/gRPC stubs aren't ours to format.
        targetExclude("build/generated/**")
    }
}

// JaCoCo — emits the XML report SonarCloud + diff-cover consume.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true }
}

tasks.test { finalizedBy(tasks.jacocoTestReport) }

// SonarCloud analysis (org.sonarqube). The plugin auto-detects sources, tests,
// compiled binaries, and the JaCoCo XML report because it runs in-build. Only
// the project identity + the generated-stub exclusions need declaring here;
// keep these in sync with sonar-project.properties (read by the maintenance
// tooling). The analysis token is supplied via the SONAR_TOKEN env var in CI.
sonar {
    properties {
        property("sonar.projectKey", "timo-jakob_tick-client-snapper")
        property("sonar.organization", "timo-jakob-github")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.exclusions", "**/build/generated/**")
        property("sonar.coverage.exclusions", "**/build/generated/**")
    }
}

// Make the OCI image name explicit while keeping the Spring Boot / Paketo
// default coordinates (docker.io/library/<rootProject.name>:<version>) so
// tooling and CI scripts can reference a stable, predictable name. We avoid
// "${project.group}/..." here: because the group is dotted (com.github.timojakob),
// OCI reference parsers would read the first component as a registry HOST, not a
// namespace — silently changing the reference. Builder/run-image pinning, a
// registry-qualified name, publish config, and BP_JVM_VERSION alignment are
// deferred to human review — see actions_requiring_review in the container audit.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    imageName.set("docker.io/library/${project.name}:${project.version}")
}

// Guarantee the JaCoCo XML is produced before analysis (Gradle doesn't order
// unrelated command-line tasks), so coverage is always reported to SonarCloud.
tasks.named("sonar") { dependsOn(tasks.named("jacocoTestReport")) }

// Keep generated protobuf/gRPC stubs out of coverage so they don't skew the
// gate (mirrors sonar.coverage.exclusions in sonar-project.properties). Done in
// a top-level afterEvaluate so the source-set class dirs are populated first and
// it runs in the valid project-evaluation context.
//
// NOTE on the exclusion pattern: the com.google.protobuf plugin compiles the
// generated stubs into build/classes/java/main/<package>/ — NOT into a
// build/generated/ subtree of the class directory. JaCoCo's classDirectories
// fileTree roots are already inside build/classes/java/main/, so the pattern
// must match the package path, not the source-generation path.
// The .proto declares `option java_package = "snapper"`, so all generated
// message + gRPC stub classes land in the top-level "snapper" package.
afterEvaluate {
    tasks.jacocoTestReport {
        classDirectories.setFrom(
            classDirectories.files.map {
                fileTree(it) { exclude("snapper/**") }
            },
        )
    }
}
