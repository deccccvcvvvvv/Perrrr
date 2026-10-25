import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("org.tukaani:xz:1.10") // public domain; decodes the Android OpenJDK .tar.xz archives
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnit()
    maxHeapSize = "384m"
    // Network/E2E tests only run when explicitly requested: ./gradlew :core:test -Pintegration
    systemProperty("cs.integration", (project.findProperty("integration") ?: "false").toString())
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Real end-to-end check on a desktop host: install a version from Mojang and launch it.
tasks.register<JavaExec>("hostE2E") {
    group = "verification"
    description = "Installs and launches a real Minecraft version on this host using the launcher core."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("pro.cslauncher.core.e2e.HostE2EKt")
    args = (project.findProperty("e2eArgs") ?: "").toString().split(" ").filter { it.isNotBlank() }
    maxHeapSize = "256m"
}

// Prints the test runtime classpath so the host E2E can run without a resident Gradle daemon (low-memory hosts).
tasks.register("printTestClasspath") {
    val cp = sourceSets["test"].runtimeClasspath
    dependsOn("testClasses")
    doLast { println("CP=" + cp.asPath) }
}
