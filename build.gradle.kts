plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.promisegraph"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val temporalVersion = "1.40.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.temporal:temporal-sdk:$temporalVersion")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")
    // Versions come from Spring Boot's dependency management.
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    mainClass = "dev.promisegraph.PromiseGraphApplication"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Print totals so CI shows what actually ran, e.g. whether the Docker-based tests were skipped.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println("Tests: ${result.testCount} run, ${result.successfulTestCount} passed, " +
                        "${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
            }
        }
    })
}

// Gate 1: fails the build if extraction precision drops below the threshold.
tasks.register<JavaExec>("evalGate") {
    group = "verification"
    description = "Evaluates commitment extraction against the labelled dataset (Gate 1)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.promisegraph.eval.ExtractionEval"
    args("--min-precision", "0.85")
}

// One-time Gmail sign-in. Saves a read-only token outside the repository.
// ./gradlew gmailAuth [-Pcredentials=path] [-Ptoken=path]
tasks.register<JavaExec>("gmailAuth") {
    group = "application"
    description = "Signs in to a personal Gmail account (read-only) and saves the token locally."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.promisegraph.gmail.GmailAuthorize"
    standardInput = System.`in`
    val home = System.getProperty("user.home")
    args(
        "--credentials", (project.findProperty("credentials") ?: "$home/.promise-graph/gmail-credentials.json").toString(),
        "--token", (project.findProperty("token") ?: "$home/.promise-graph/gmail-token.json").toString(),
    )
}

// Gate 2: links, ripple, absences and cover picks on the hand-written core scenario.
tasks.register<JavaExec>("evalGate2") {
    group = "verification"
    description = "Evaluates links, ripple and handoff planning on the core scenario (Gate 2)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.promisegraph.eval.CoordinationEval"
}

// Gate 3: simulated leads approve at least half the recovery plans; every plan names real promises.
tasks.register<JavaExec>("evalGate3") {
    group = "verification"
    description = "Evaluates recovery plans against simulated leads' stated preferences (Gate 3)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.promisegraph.eval.RecoveryEval"
}

tasks.named("check") {
    dependsOn("evalGate", "evalGate2", "evalGate3")
}
