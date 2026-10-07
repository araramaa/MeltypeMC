plugins {
    kotlin("jvm") version "2.4.20"
}

group = "net.oyasai.meltype"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Minecraft 26.2 Mojang Official & Fabric API & GLFW & SLF4J
    compileOnly(fileTree("libs") { include("*.jar") })
    testImplementation(fileTree("libs") { include("*.jar") })

    // Unit Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("meltype-mc")
    archiveVersion.set("1.0.0")
    archiveClassifier.set("")

    manifest {
        attributes(
            "Fabric-Mapping-Namespace" to "official"
        )
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to (project.version as String)
            )
        )
    }
}

kotlin {
    jvmToolchain(25)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
