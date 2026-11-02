plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.eddiew.commandersim"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eddiew.commandersim"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.3.09"
    }

    signingConfigs {
        // A fixed key checked into the repo so every build (local or CI) can install over the previous one.
        // Testing builds only; a release build for the Play Store will need a private key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Not debuggable, so Android runs it fully optimised (Forge games are much faster).
            // Same key as debug builds, so it installs over them.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    sourceSets {
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/forgepack-assets"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        noCompress += "apk"
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(project(":engine-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Bundle the Forge pack (engine + card data, see :forgepack) as a fallback, for when no newer pack has been
// downloaded. bundled.txt identifies it: Forge commit, engine API version, Kotlin version.
val bundleForgePack by tasks.registering {
    dependsOn(":forgepack:assembleRelease")
    val outDir = layout.buildDirectory.dir("generated/forgepack-assets/forge")
    val packApk = rootProject.file("forgepack/build/outputs/apk/release/forgepack-release-unsigned.apk")
    val forgeVersion = rootProject.file("../forge.version")
    val apiFile = rootProject.file("engine-api/src/main/kotlin/com/eddiew/commandersim/sim/GameTypes.kt")
    inputs.file(packApk)
    inputs.file(forgeVersion)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        packApk.copyTo(dir.resolve("forge-pack.apk"), overwrite = true)
        dir.resolve("bundled.txt").writeText(
            "forgeCommit=${forgeVersion.readText().trim()}\napiVersion=${Regex("ENGINE_API_VERSION = (\\d+)").find(apiFile.readText())!!.groupValues[1]}\nkotlin=${libs.versions.kotlin.get()}\nsize=${packApk.length()}\n",
        )
    }
}
tasks.named("preBuild") { dependsOn(bundleForgePack) }
