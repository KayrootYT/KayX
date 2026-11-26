plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Generate a monotonically increasing app version from the full Git history.
val kayxCommitCount = runCatching {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootProject.projectDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0)
    output.toInt().coerceAtLeast(1)
}.getOrDefault(6)
val kayxVersionCode = (100_000L + kayxCommitCount).coerceAtMost(2_100_000_000L).toInt()
val kayxVersionName = "0.4.$kayxCommitCount"

android {
    namespace = "com.kayx.manager"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.kayx.manager"
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = kayxVersionCode
        versionName = kayxVersionName
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KAYX_KEYSTORE_PATH") ?: "missing-release-key.jks")
            storePassword = System.getenv("KAYX_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KAYX_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KAYX_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; aidl = true }
    packaging { resources { excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST") } }
    sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/res/folderArtwork"))
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
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
    // RAR 4/5/7 extraction only (RAR archive creation is not supported by this library license).
    implementation("com.github.junrar:junrar:8.1.2")
    implementation("org.tukaani:xz:1.9")
    // SFTP with strict known_hosts verification and SMB2/3 client support
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.hierynomus:smbj:0.13.0")
    // Shizuku (official API + provider) – privileged shell via a user-granted Shizuku service
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Offline QR / barcode scanner (Apache 2.0, no Google Play services, no network)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
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
val extractFolderArtworkResources = tasks.register<Copy>("extractFolderArtworkResources") {
    val folderArtworkZip = file("kayx-folder-icons-android-resources.zip")
    val homeArtworkZip = file("kayx-home-screen-icons-resources.zip")
    val homeNavArtworkZip = file("kayx-home-nav-3d-icons-resources.zip")
    val extraHomeArtworkZip = file("kayx-extra-3d-icons-android-resources.zip")
    // Prefer the latest combined Home + Navigation artwork when filenames overlap.
    // EXCLUDE makes the first source win and avoids Gradle duplicate-resource failures.
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from(zipTree(extraHomeArtworkZip)) {
        include("drawable-nodpi/**")
    }
    from(zipTree(homeNavArtworkZip)) {
        include("drawable-nodpi/**")
    }
    from(zipTree(homeArtworkZip)) {
        include("drawable-nodpi/**")
    }
    from(zipTree(folderArtworkZip)) {
        include("drawable-nodpi/**")
    }
    into(layout.buildDirectory.dir("generated/res/folderArtwork"))
    onlyIf { folderArtworkZip.isFile || homeArtworkZip.isFile || homeNavArtworkZip.isFile || extraHomeArtworkZip.isFile }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(cargoNdkBuild, extractFolderArtworkResources)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
