import websnag.buildlogic.WebSnagVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val releaseTasksRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').contains("release", ignoreCase = true)
}
val webSnagVersion = WebSnagVersion.resolve(
    releaseTag = providers.gradleProperty("websnagReleaseTag").orNull,
    releaseBuildRequested = releaseTasksRequested,
)

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
            if (releaseTasksRequested) {
                val keystorePath = System.getenv("KEYSTORE_PATH")
                    ?: error("Release signing requires KEYSTORE_PATH.")
                val storePasswordValue = System.getenv("KEYSTORE_PASSWORD")
                    ?: error("Release signing requires KEYSTORE_PASSWORD.")
                val keyAliasValue = System.getenv("KEY_ALIAS")
                    ?: error("Release signing requires KEY_ALIAS.")
                val keyPasswordValue = System.getenv("KEY_PASSWORD")
                    ?: error("Release signing requires KEY_PASSWORD.")
                val keystoreFile = rootProject.file(keystorePath)
                check(keystoreFile.isFile) { "Release signing keystore does not exist: $keystorePath" }
                storeFile = keystoreFile
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
