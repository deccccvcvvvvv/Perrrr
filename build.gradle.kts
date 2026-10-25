plugins {
    id("com.android.application") version "8.5.2" apply false
    kotlin("jvm") version "2.0.20" apply false
    kotlin("android") version "2.0.20" apply false
    kotlin("plugin.serialization") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // Only applied with -Ppaparazzi (JVM screenshot rendering of the Compose UI, see app/src/screenshotTest).
    id("app.cash.paparazzi") version "1.3.4" apply false
}
