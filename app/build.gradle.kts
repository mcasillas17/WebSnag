import com.android.tools.build.bundletool.commands.DumpCommand
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import websnag.buildlogic.ReleaseArtifactIdentity
import websnag.buildlogic.ReleaseSigning
import websnag.buildlogic.WebSnagVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val signingOptIn = providers.gradleProperty("websnagReleaseSigning").orNull
require(signingOptIn == null || signingOptIn == "true" || signingOptIn == "false") {
    "Use -PwebsnagReleaseSigning=true only for explicit signed release builds."
}
val releaseSigningEnabled = signingOptIn == "true"
val webSnagVersion = WebSnagVersion.resolve(
    providers.gradleProperty("websnagReleaseTag").orNull,
    releaseSigningEnabled,
)
val releaseSigning = if (releaseSigningEnabled) {
    check(!gradle.startParameter.isConfigurationCacheRequested &&
        !gradle.startParameter.isBuildCacheEnabled &&
        gradle.startParameter.logLevel != LogLevel.DEBUG &&
        !gradle.startParameter.isBuildScan) {
        "Release signing requires --no-configuration-cache --no-build-cache; debug logging and scans are forbidden."
    }
    ReleaseSigning.load(System.getenv(), rootProject.projectDir.toPath())
} else null

// Guard actual task nodes, not requested names: assemble, build and assR all reach this gate.
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    inputs.property("enabled", releaseSigningEnabled)
    doLast {
        check(inputs.properties["enabled"] == true) {
            "Release tasks require -PwebsnagReleaseSigning=true and a valid websnagReleaseTag. See docs/releasing.md."
        }
    }
}
tasks.configureEach {
    if (name != "requireReleaseSigning" && name.contains("release", ignoreCase = true)) {
        dependsOn(requireReleaseSigning)
    }
    if (name == "signingReport" && releaseSigningEnabled) {
        doFirst { error("Signing reports are disabled while release credentials are loaded.") }
    }
}

android {
    namespace = "websnag.elopenmike.com"
    compileSdk = 35

    defaultConfig {
        applicationId = "websnag.elopenmike.com"
        minSdk = 26
        targetSdk = 35
        versionCode = webSnagVersion.versionCode
        versionName = webSnagVersion.versionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            enableV2Signing = true
            enableV3Signing = true
            if (releaseSigning != null) {
                storeFile = releaseSigning.storeFile.toFile()
                storeType = releaseSigning.storeType
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Architecture Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // DataStore & Serialization
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // Jetpack Compose & Material 3
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Tooling & Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Local unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.register("printWebSnagVersion") {
    group = "verification"
    description = "Prints the resolved Android version metadata as key-value pairs."
    outputs.upToDateWhen { false }

    doLast {
        println("versionName=${webSnagVersion.versionName}")
        println("versionCode=${webSnagVersion.versionCode}")
    }

}

tasks.register("verifyBundleIdentity") {
    group = "verification"
    description = "Checks an existing AAB's signature, package, version and non-debuggability; does not publish."
    notCompatibleWithConfigurationCache("Inspects current artifact and expected public certificate.")
    mustRunAfter("bundleRelease")
    doLast {
        val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile.toPath()
        val digest = ReleaseSigning.certificateDigest(System.getenv("WEBSNAG_SIGNING_CERT_SHA256"))
        val expected = WebSnagVersion.resolve(providers.gradleProperty("websnagReleaseTag").orNull, true)
        ReleaseArtifactIdentity.verifyBundle(bundle, digest)
        val xml = ByteArrayOutputStream()
        PrintStream(xml, true, Charsets.UTF_8).use { output ->
            DumpCommand.builder()
                .setBundlePath(bundle)
                .setDumpTarget(DumpCommand.DumpTarget.MANIFEST)
                .setModuleName("base")
                .setOutputStream(output)
                .build().execute()
        }
        ReleaseArtifactIdentity.verifyManifest(xml.toString(Charsets.UTF_8), expected)
        println("AAB identity verified: ${expected.versionName} (${expected.versionCode}), certificate SHA-256=$digest")
    }
}
