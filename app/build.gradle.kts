plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional: `./gradlew :app:recordPaparazziDebug -Ppaparazzi` renders landscape screenshots of the pixel UI on the JVM.
val withPaparazzi = providers.gradleProperty("paparazzi").isPresent
if (withPaparazzi) apply(plugin = "app.cash.paparazzi")

android {
    if (withPaparazzi) sourceSets["test"].java.srcDir("src/screenshotTest/kotlin")
    namespace = "pro.cslauncher.app"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "pro.cslauncher.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=none") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    signingConfigs {
        // Shared *debug* key (standard public debug credentials, not a secret) so that every
        // debug APK - local or CI - has the same signature and installs as an update instead of
        // failing with "App not installed". Release signing is configured separately by the publisher.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        // libcsglfw.so must exist as a real file: the game's LWJGL loads it by absolute path.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module") }
    }
    // Bundled runtimes (.tar.xz) and component packs (.zip) are already compressed; store them as-is.
    androidResources { noCompress += listOf("xz", "zip") }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    testImplementation("junit:junit:4.13.2")
}

// Guards against Java 11+ APIs that compile against the JDK but are absent from Android's class library at minSdk
// (e.g. Files.readString, Stream.toList) — such calls would only fail at runtime with NoSuchMethodError.
val checkRegexIcu by tasks.registering(Exec::class) {
    group = "verification"
    description = "Flags regex literals that the JDK accepts but Android's ICU regex engine rejects (e.g. a bare '}')"
    commandLine("python3", rootProject.file("tools/check_regex_icu.py").path,
        rootProject.file("core/src/main").path, file("src/main").path)
}
val checkAndroidApi by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks :core and :app bytecode against the SDK's api-versions.xml for minSdk"
    dependsOn(":core:jar", "compileDebugKotlin", checkRegexIcu)
    val sdk = android.sdkDirectory
    val minSdk = android.defaultConfig.minSdk!!
    commandLine("python3", rootProject.file("tools/check_android_api.py").path,
        "$sdk/platforms/android-${android.compileSdk}/data/api-versions.xml", "$minSdk",
        project(":core").layout.buildDirectory.dir("classes/kotlin/main").get().asFile.path,
        layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile.path)
}
tasks.named("check") { dependsOn(checkAndroidApi) }
