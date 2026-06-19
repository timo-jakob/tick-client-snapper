import com.google.protobuf.gradle.id

plugins {
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
    application
    id("com.google.protobuf") version "0.9.6"
    id("nebula.release") version "21.0.0"
    `jvm-test-suite`
    // Added by /development:bootstrap — CI + pre-commit prerequisites.
    id("com.diffplug.spotless") version "7.0.2"
    jacoco
}

repositories {
    mavenCentral()
}

group = "com.github.timojakob"

val grpcVersion = "1.78.0"
val protoVersion = "4.34.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // grpc (see https://github.com/grpc/grpc-java)
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")

    implementation("com.google.protobuf:protobuf-java:$protoVersion")
    implementation("com.google.protobuf:protobuf-java-util:$protoVersion")

    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // necessary for Java 9+
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protoVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
            }
        }
    }
}

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

// Keep generated protobuf/gRPC stubs out of coverage so they don't skew the
// gate (mirrors sonar.coverage.exclusions in sonar-project.properties). Done in
// a top-level afterEvaluate so the source-set class dirs are populated first and
// it runs in the valid project-evaluation context.
afterEvaluate {
    tasks.jacocoTestReport {
        classDirectories.setFrom(
            classDirectories.files.map {
                fileTree(it) { exclude("**/build/generated/**") }
            },
        )
    }
}
