plugins { id("com.android.application") }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
android {
    namespace = "dev.codex.android"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    defaultConfig {
        applicationId = "dev.codex.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
