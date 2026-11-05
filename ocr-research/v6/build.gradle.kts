plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "io.github.mymoney.ocr.research"
    compileSdk = 35
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro", "generated/sdk/proguard-rules.pro"); ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main") {
        java.srcDirs("generated/sdk/src/main/java", "bridge")
        assets.srcDirs("generated/assets", "notices")
        manifest.srcFile("generated/sdk/src/main/AndroidManifest.xml")
    }
}
dependencies {
    implementation(project(":billkit"))
    compileOnly(files("generated/runtime/onnxruntime-reduced-dual.aar", "generated/runtime/opencv-minimal-dual.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
