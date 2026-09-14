/*
 * Copyright 2022 LLC Campus.
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":"))

    implementation(libs.parserTestsSdk)
    implementation(libs.log4jCore)
}
