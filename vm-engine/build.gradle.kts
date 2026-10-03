plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

val fixtureAssets = layout.buildDirectory.dir("generated/vmFixtureAssets")
val prepareVmFixtureAssets by tasks.registering(Sync::class) {
    from(rootProject.file("dist/vm-fixture")) {
        include("kernel", "boot-proof.cpio.gz", "manifest.json")
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
