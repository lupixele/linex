import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.linex.app"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.linex.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.6.1-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/liblinex_qemu_aarch64.so"
            // Required so libproot.so and native binaries are extracted to nativeLibraryDir
            // with executable permissions rather than kept inside the uncompressed APK.
            useLegacyPackaging = true
            pickFirsts += listOf("**/*.so")
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version"
            )
        }
    }

    aaptOptions {
        noCompress += listOf("zst", "xz", "gz", "sh", "conf")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }
}

// A release catalogue must never advertise a VM in an APK missing its engine.
val verifyBundledVmRuntime by tasks.registering {
    val catalogue = layout.projectDirectory.file("src/main/assets/vm/image.json")
    val nativeRoot = providers.gradleProperty("vmNativeDir")
        .map { rootProject.file(it) }.orElse(rootProject.file("vm-engine/src/main/jniLibs"))
    inputs.file(catalogue).optional()
    inputs.dir(nativeRoot).optional()
    doLast {
        if (!catalogue.asFile.exists()) return@doLast
        val expected = mapOf(
            "arm64-v8a" to "9ae97c0f477ebcf627c726a70930013a17ebb629f2b5aa7e95ce4f4e4d6c8564",
            "x86_64" to "8a9928fe10bdc9a47920db8828edeb64c154f04975811634dd9be90d4bf822bb",
        )
        for ((abi, pin) in expected) {
            val library = File(nativeRoot.get(), "$abi/liblinex_qemu_aarch64.so")
            check(library.isFile) { "Verified VM runtime missing for $abi. Set -PvmNativeDir to the accepted runtime artifacts." }
            val digest = MessageDigest.getInstance("SHA-256")
            library.inputStream().use { source ->
                val buffer = ByteArray(1048576)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == pin) { "VM runtime bytes differ from the accepted $abi producer" }
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(verifyBundledVmRuntime)
}

dependencies {
    implementation(project(":vm-engine"))
    implementation(project(":vm-images"))
    implementation(project(":vm-console"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(libs.kotlinx.serialization.json)
}
