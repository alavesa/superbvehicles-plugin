// SuperbVehicles - Gradle Kotlin DSL build (the team's build). The Maven pom.xml in this folder is the build that
// was used to compile and test the plugin on the author's machine (Gradle could not be installed there), so this
// file is kept equivalent to the pom but has NOT been run. Report any discrepancy.
plugins {
    java
}

group = "fi.alavesa"
version = "0.1.0"
description = "Data-driven Blockbench vehicles for Paper 26.x"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
    // the server's own netty, compile-only: the same-port pack host hooks the listening channel (compat package)
    compileOnly(files("libs/netty-common-4.2.16.Final.jar", "libs/netty-buffer-4.2.16.Final.jar", "libs/netty-transport-4.2.16.Final.jar", "libs/netty-handler-4.2.16.Final.jar", "libs/netty-resolver-4.2.16.Final.jar"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.papermc.paper:paper-api:26.2.build.135-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("paper-plugin.yml") { expand("project" to mapOf("version" to project.version)) }
}

tasks.test { useJUnitPlatform() }

tasks.jar { archiveFileName.set("SuperbVehicles-${project.version}.jar") }

// Regenerate the shipped placeholder models + defaults-index.txt from src/main/resources/vehicles/*.yml
tasks.register<JavaExec>("generateRoster") {
    group = "superbvehicles"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("fi.alavesa.superbvehicles.tools.GenerateRoster")
    args("src/main/resources")
}
