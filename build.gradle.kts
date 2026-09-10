plugins {
    `java-library`
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
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

javafx {
    version = "21.0.4"
    modules("javafx.controls")
}

application {
    mainClass = "doom.ui.JfxDoomApp"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Forward selected `-Dkey=value` flags from the Gradle JVM
    // into the test JVM so diagnostic probes (e.g. E1M1ScreenshotProbe)
    // can be armed without a separate runner.
    listOf("e1m1.probe", "fx3.probe", "doom.wad").forEach { k ->
        System.getProperty(k)?.let { systemProperty(k, it) }
    }
}

// Example: ./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --minutes 10"
tasks.register<JavaExec>("train") {
    group = "application"
    description = "Learn an E1M1 route without opening a window"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("doom.ai.Trainer")
    maxHeapSize = "1g"
    args(providers.gradleProperty("trainArgs").map {
        org.apache.tools.ant.types.Commandline.translateCommandline(it).toList()
    }.getOrElse(emptyList()))
}
