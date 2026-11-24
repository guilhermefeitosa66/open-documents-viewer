plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val pdfium = rootProject.file("pdfium/prebuilt")
if (!pdfium.resolve("include/fpdfview.h").exists()) {
    throw GradleException("PDFium binaries missing. Run tools/fetch-pdfium.sh first.")
}

android {
    namespace = "io.github.guilhermefeitosa66.opendocumentsviewer"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // Fixed after the first publication.
        applicationId = "io.github.guilhermefeitosa66.opendocumentsviewer"
        minSdk = 26
        targetSdk = 36
        versionCode = 132
        versionName = "0.32.1"
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // PDF: PDFium (BSD-3-Clause / Apache-2.0) behind an own JNI layer, loaded only when a PDF opens.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets["main"].jniLibs.srcDirs(pdfium.resolve("jniLibs"))
    sourceSets["main"].assets.srcDirs("src/main/assets", pdfium.resolve("assets"))

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

    // HTML parser for the formatted view of .html files (MIT). Only touched when an HTML file opens.
    implementation("org.jsoup:jsoup:1.24.1")
    // SVG rendering (Apache-2.0). Only touched when an SVG file opens.
    implementation("com.caverock:androidsvg-aar:1.4")
    // YAML parser for the tree view of .yaml files (Apache-2.0, no java.beans). Only touched when one opens.
    implementation("org.snakeyaml:snakeyaml-engine:2.9")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM.
    testImplementation("org.json:json:20240303")
}
