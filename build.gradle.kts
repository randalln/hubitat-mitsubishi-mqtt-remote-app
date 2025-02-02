// SPDX-FileCopyrightText: 2025 NONE <none>
//
// SPDX-License-Identifier: CC0-1.0

plugins {
    id("groovy")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.codehaus.groovy:groovy-all:2.4.21")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}