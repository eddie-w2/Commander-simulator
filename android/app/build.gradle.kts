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
        versionCode = 3
        versionName = "0.1.03"
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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Forge targets Java 17; backport newer java.util APIs for older Android versions.
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/*.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/versions/*/module-info.class",
                "module-info.class",
            )
        }
    }
    testOptions {
        unitTests.all {
            it.maxHeapSize = "3g"
            it.jvmArgs("-Xss64m")
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Forge game engine: built by tools/build-forge.sh into libs/forge (not checked in).
    implementation(fileTree(mapOf("dir" to "libs/forge", "include" to listOf("*.jar"))))
    implementation(libs.guava)
    implementation(libs.commons.lang3)
    implementation(libs.commons.text)
    implementation(libs.commons.math3)
    implementation(libs.jgrapht.core)
    implementation(libs.sentry)
    implementation(libs.tinylog.api)
    implementation(libs.tinylog.impl)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

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
