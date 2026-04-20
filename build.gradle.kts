plugins {
    `java-library`
    application
}

group = "doom"
version = "0.1.0"

// Target Java 21 bytecode (per CLAUDE.md) using whatever JDK runs Gradle
// (currently Corretto 25). If a Java 21 toolchain is installed later,
// switch to `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // Placeholder — real main class will arrive with d_main port (§10 step 11).
    mainClass = "doom.system.DMain"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
