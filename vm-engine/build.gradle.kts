plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

val fixtureAssets = layout.buildDirectory.dir("generated/vmFixtureAssets")
val initramfsAssetName = "boot-proof.initramfs"
val prepareVmFixtureAssets by tasks.registering(Sync::class) {
    // Copy/Sync renaming rules are not fingerprinted automatically. Changing
    // the packaged alias must invalidate previously copied fixture assets.
    inputs.property("initramfsAssetName", initramfsAssetName)
    inputs.property("networkInitramfsAssetName", "network-proof.initramfs")
    from(rootProject.file("dist/vm-fixture")) {
        include("kernel", "boot-proof.cpio.gz", "manifest.json")
        // Android asset packaging interprets .gz as a precompressed asset,
        // removes the suffix and exposes decompressed bytes. Preserve the
        // original compressed fixture and its pinned SHA under an opaque name.
        rename("boot-proof\\.cpio\\.gz", initramfsAssetName)
    }
    from(rootProject.file("dist/vm-network-fixture")) {
        into("network")
        include("kernel", "network-proof.cpio.gz", "manifest.json")
        rename("network-proof\\.cpio\\.gz", "network-proof.initramfs")
    }
    into(fixtureAssets)
}

android {
    namespace = "com.linex.vm"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions { targetSdk = 34 }
    lint { targetSdk = 34 }
    // Proof APKs must contain the exact ELF whose digest/ABI were verified.
    // The module is not yet linked into the production app.
    packaging { jniLibs.keepDebugSymbols += "**/liblinex_qemu_aarch64.so" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main") {
            val nativeDirectory = providers.gradleProperty("vmNativeDir").orNull
                ?.let { rootProject.file(it) } ?: file("src/main/jniLibs")
            jniLibs.setSrcDirs(listOf(nativeDirectory))
        }
        getByName("androidTest") {
            // Preserve the producer's build dependency as well as its path.
            assets.srcDir(prepareVmFixtureAssets.map { it.destinationDir })
        }
    }
}

tasks.matching {
    it.name.contains("AndroidTest") && (
        (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
            (it.name.startsWith("generate") && it.name.contains("Lint") && it.name.endsWith("Model")) ||
            it.name.startsWith("lint")
        )
}.configureEach {
    dependsOn(prepareVmFixtureAssets)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
