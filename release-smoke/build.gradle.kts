import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// A standalone instrumentation host keeps its runtime dependencies inside its own APK.
// An app's androidTest variant omits libraries supplied by the Debug target, whose
// original class names are unavailable when that target is replaced by an R8 APK.
android {
    namespace = "com.flue.launcher.releasesmoke"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.gaozai.surface.test"
        minSdk = 27
        targetSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets.getByName("main").kotlin.apply {
        srcDir("../app/src/androidTest/kotlin")
        include("**/LauncherSmokeTest.kt", "**/ReleaseUiSmokeTest.kt")
    }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
