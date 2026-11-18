plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.kayx.manager"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.kayx.manager"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; aidl = true }
    packaging { resources { excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST") } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Original DEX disassembly engine (Apache 2.0)
    implementation("org.smali:baksmali:2.5.2")
    implementation("org.smali:smali:2.5.2")
    // APK signing (v1+v2+v3) + self-signed key generation
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // tar / tar.gz / tar.bz2 / tar.xz / 7z / gz / bz2 / xz extraction
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("org.tukaani:xz:1.9")
    // Shizuku (official API + provider) – privileged shell via a user-granted Shizuku service
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

// Rust core (rust/kayx-core) -> app/src/main/jniLibs/<abi>/libkayx_core.so via cargo-ndk.
// Skipped automatically when cargo-ndk is not installed; the app then uses the Kotlin fallbacks.
val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    workingDir = rootProject.file("rust/kayx-core")
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", file("src/main/jniLibs").absolutePath, "build", "--release")
    onlyIf {
        runCatching { ProcessBuilder("cargo", "ndk", "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(cargoNdkBuild) }

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
