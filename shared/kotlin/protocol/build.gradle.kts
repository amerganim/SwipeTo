plugins {
    kotlin("jvm")
    `java-library`
}

group = "dev.swipeto"
version = "1.0.0"

// Android consumes this module, so emit Java 17 bytecode whatever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Shared conformance vectors live at the repository root.
    systemProperty("swipeto.vectors", rootDir.resolve("../../docs/test-vectors").canonicalPath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
