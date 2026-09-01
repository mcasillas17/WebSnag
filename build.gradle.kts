// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    configurations.classpath {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.85.2",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
        )
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
