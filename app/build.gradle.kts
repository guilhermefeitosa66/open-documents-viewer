plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.guilhermefeitosa66.opendocumentsviewer"
    compileSdk = 36

    defaultConfig {
        // Fixed after the first publication.
        applicationId = "io.github.guilhermefeitosa66.opendocumentsviewer"
        minSdk = 26
        targetSdk = 36
        versionCode = 106
        versionName = "0.7.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // There is no release key yet: release builds are only for local measurements.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Markdown parser (BSD-2-Clause).
    val commonmark = "0.27.0"
    implementation("org.commonmark:commonmark:$commonmark")
    implementation("org.commonmark:commonmark-ext-gfm-tables:$commonmark")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:$commonmark")
    implementation("org.commonmark:commonmark-ext-task-list-items:$commonmark")
    implementation("org.commonmark:commonmark-ext-autolink:$commonmark")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM.
    testImplementation("org.json:json:20240303")
}
