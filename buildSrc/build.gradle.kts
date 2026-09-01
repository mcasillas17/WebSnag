plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}
