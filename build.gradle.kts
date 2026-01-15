plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.textsearch"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin coroutines for parallel processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.textsearch.demo.DemoKt")
}
