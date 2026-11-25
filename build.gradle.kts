plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "com.salyvn.mcstage"
version = "0.55.0"

repositories {
    mavenCentral()
}

dependencies {
    // Scenario files are hand-written YAML, so they are parsed with SafeConstructor: no tag
    // resolution and no object instantiation, because a scenario is data and never code.
    implementation("org.yaml:snakeyaml:2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.salyvn.mcstage.MainKt"
    applicationName = "mcstage"
}

tasks.test {
    useJUnitPlatform()
    // The runner tests start a real child JVM and drive it through its own stdin and stdout, so the
    // test JVM has to hand them the java binary it is itself running on and the classpath the fake
    // server lives on. Neither can be discovered from inside the child.
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dmcstage.test.classpath=${sourceSets.test.get().runtimeClasspath.asPath}")
        },
    )
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// One source of truth for the version: the build stamps it into a resource the CLI reads back, so
// the number in `--version` cannot drift from the number in build.gradle.kts.
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("mcstage-version.properties") {
        expand("version" to project.version.toString())
    }
}

// The thin jar gets a classifier so it cannot overwrite the shaded artifact that users download
// and that the release checksums cover.
tasks.jar {
    archiveClassifier = "thin"
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveBaseName = "mcstage"
    manifest {
        attributes["Main-Class"] = "com.salyvn.mcstage.MainKt"
        attributes["Implementation-Version"] = project.version.toString()
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
