// The Forge pack: Forge's engine + our adapter (+ card data as an asset), built as an APK that the app loads at
// runtime with DexClassLoader. Shared types come from :engine-api, which the app provides at runtime.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.eddiew.commandersim.forgepack"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eddiew.commandersim.forgepack"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false // Forge uses reflection
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
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
            it.testLogging { showStandardStreams = true; events("passed", "skipped", "failed") }
        }
    }
}

dependencies {
    // Built by tools/build-forge.sh (not checked in).
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

    compileOnly(project(":engine-api"))
    testImplementation(project(":engine-api"))
    testImplementation(libs.junit)
}
