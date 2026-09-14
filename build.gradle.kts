/*
 * Copyright 2022 LLC Campus.
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

group = "me.campusapp.parsers"
version = "0.1.0"

dependencies {
    api(libs.parserSdk)
    implementation(libs.jsoup)

    testImplementation(libs.parserTestsSdk)
}

tasks.withType<AbstractTestTask> {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events = setOf(
            TestLogEvent.SKIPPED,
            TestLogEvent.PASSED,
            TestLogEvent.FAILED
        )
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}

java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/campus-mobile/campus-parser-template")
            credentials {
                username = project.findProperty("gpr.user")?.toString() ?: System.getenv("GPR_USERNAME")
                password = project.findProperty("gpr.key")?.toString() ?: System.getenv("GPR_TOKEN")
            }
        }
    }
    publications {
        register<MavenPublication>("space") {
            from(components.getByName("java"))
        }
    }
}
