plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.linex.vm.images"
    compileSdk = 34
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    testOptions { targetSdk = 34 }
    lint { targetSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation("org.tukaani:xz:1.12")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
