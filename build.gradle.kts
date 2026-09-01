import org.gradle.api.artifacts.component.ModuleComponentIdentifier

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    configurations.classpath {
        resolutionStrategy.force(
            "org.apache.commons:commons-lang3:3.18.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.bouncycastle:bcprov-jdk18on:1.85.2",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
            "org.jdom:jdom2:2.0.6.1",
        )
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val patchedTransitiveDependencies = listOf(
    "com.google.guava:guava:33.4.0-jre",
    "io.netty:netty-buffer:4.1.137.Final",
    "io.netty:netty-codec:4.1.137.Final",
    "io.netty:netty-codec-http:4.1.137.Final",
    "io.netty:netty-codec-http2:4.1.137.Final",
    "io.netty:netty-codec-socks:4.1.137.Final",
    "io.netty:netty-common:4.1.137.Final",
    "io.netty:netty-handler:4.1.137.Final",
    "io.netty:netty-handler-proxy:4.1.137.Final",
    "io.netty:netty-resolver:4.1.137.Final",
    "io.netty:netty-transport:4.1.137.Final",
    "io.netty:netty-transport-native-unix-common:4.1.137.Final",
    "org.apache.commons:commons-lang3:3.18.0",
    "org.apache.httpcomponents:httpclient:4.5.14",
    "org.bouncycastle:bcpkix-jdk18on:1.85",
    "org.bouncycastle:bcprov-jdk18on:1.85.2",
    "org.bouncycastle:bcutil-jdk18on:1.85",
)

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(*patchedTransitiveDependencies.toTypedArray())
    }
}

val minimumSecureBuildDependencyVersions = mapOf(
    "com.google.guava:guava" to "32.0.0",
    "io.netty:netty-buffer" to "4.1.137",
    "io.netty:netty-codec" to "4.1.137",
    "io.netty:netty-codec-http" to "4.1.137",
    "io.netty:netty-codec-http2" to "4.1.137",
    "io.netty:netty-codec-socks" to "4.1.137",
    "io.netty:netty-common" to "4.1.137",
    "io.netty:netty-handler" to "4.1.137",
    "io.netty:netty-handler-proxy" to "4.1.137",
    "io.netty:netty-resolver" to "4.1.137",
    "io.netty:netty-transport" to "4.1.137",
    "io.netty:netty-transport-native-unix-common" to "4.1.137",
    "org.apache.commons:commons-lang3" to "3.18.0",
    "org.apache.httpcomponents:httpclient" to "4.5.13",
    "org.bitbucket.b_c:jose4j" to "0.9.6",
    "org.bouncycastle:bcpkix-jdk18on" to "1.84",
    "org.bouncycastle:bcprov-jdk18on" to "1.84",
    "org.bouncycastle:bcutil-jdk18on" to "1.84",
    "org.jdom:jdom2" to "2.0.6.1",
)

fun numericVersionParts(version: String): List<Int> =
    Regex("""\d+""").findAll(version).map { it.value.toInt() }.toList()

fun compareNumericVersions(left: String, right: String): Int {
    val leftParts = numericVersionParts(left)
    val rightParts = numericVersionParts(right)
    val partCount = maxOf(leftParts.size, rightParts.size)

    for (index in 0 until partCount) {
        val comparison = (leftParts.getOrNull(index) ?: 0)
            .compareTo(rightParts.getOrNull(index) ?: 0)
        if (comparison != 0) {
            return comparison
        }
    }
    return 0
}

tasks.register("verifyBuildDependencySecurity") {
    group = "verification"
    description = "Fails when resolved build or tooling dependencies contain known vulnerable versions."

    doLast {
        val configurationsToInspect = buildList {
            add(rootProject.buildscript.configurations.getByName("classpath"))
            allprojects.forEach { project ->
                addAll(project.configurations.filter { it.isCanBeResolved })
            }
        }
        val vulnerableDependencies = linkedSetOf<String>()

        configurationsToInspect.forEach { configuration ->
            configuration.incoming.resolutionResult.allComponents.forEach { component ->
                val identifier = component.id as? ModuleComponentIdentifier
                    ?: return@forEach
                val module = "${identifier.group}:${identifier.module}"
                val minimumVersion = minimumSecureBuildDependencyVersions[module]
                    ?: return@forEach
                if (compareNumericVersions(identifier.version, minimumVersion) < 0) {
                    vulnerableDependencies +=
                        "$module:${identifier.version} in ${configuration.name} (minimum $minimumVersion)"
                }
            }
        }

        if (vulnerableDependencies.isNotEmpty()) {
            throw GradleException(
                vulnerableDependencies.joinToString(
                    prefix = "Vulnerable build dependencies remain:\n- ",
                    separator = "\n- ",
                ),
            )
        }
    }
}
