import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    /* AGP 9 compiles Kotlin itself: no Kotlin Android plugin. */
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = "0.1.0"

/* The release key, from keystore.properties at the project's root - not in
 * git: storeFile, storePassword, keyAlias, keyPassword. Without it, a
 * release build comes out unsigned. */
val keystore = rootProject.file("keystore.properties").takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

/* The APKs' and bundles' names: LaserMediaPlayer-0.1.0-release.apk rather
 * than app-release.apk. */
base {
    archivesName = "LaserMediaPlayer-$appVersion"
}

android {
    namespace = "io.github.pierrebog.lasermediaplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.pierrebog.lasermediaplayer"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = appVersion
        /* The ABIs with a Java runtime for the Blu-ray menus, in
         * src/main/assets/bdj/: jre8-<abi>.zip. */
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        /* Installable beside the release: its own package, files and
         * settings, and its own name in the launcher (src/debug/res). */
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        /* libvlc.so is large; keep it compressed in the APK. */
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    /* libVLC 4 built by libvlcjni with laser, liblaser, libdvdcss and
     * libaacs support - see app/libs/README.md. */
    implementation(files("libs/libvlc-release.aar"))
    implementation(libs.androidx.annotation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
