import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.autobook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.autobook"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = providers.gradleProperty("autobookTestRunner")
            .getOrElse("com.example.autobook.HistoryInstrumentation")
        versionCode = 101
        versionName = "0.33.1"
    }

    // 签名信息放在仓库之外（~/.autobook-keys/keystore.properties），不进版本库。
    // 文件不存在时 release 就不签名，assembleRelease 会产出 -unsigned.apk。
    val keystoreFile = File(System.getProperty("user.home"), ".autobook-keys/keystore.properties")
    val keystoreProps: Properties? = if (keystoreFile.exists()) {
        Properties().apply { keystoreFile.inputStream().use { stream -> load(stream) } }
    } else null

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // 识别链路（分类/抽取/校验/OCR 归一化）在这里
    implementation(project(":billkit"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // 悬浮窗里跑 Compose 要自己当 ViewTreeLifecycleOwner / SavedStateRegistryOwner
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 真·液体玻璃：RenderNode + RuntimeShader(AGSL) 做真实折射/色散。
    // MIT 许可，Android 13(API 33) 以下只保持透明不渲染，所以 API<33 仍用我们自己的 GlassSurface 兜底。
    // 上游 AAR 的 aar-metadata 声明 minCompileSdk=37，而本项目 compileSdk=35（AGP 8.7 上限）。
    // 库本身 minSdk 24、只用到 API 33 的 RuntimeShader/RenderNode，把元数据里的
    // minCompileSdk 改成 35 重新打包后放进 app/libs，不依赖 JitPack/Maven。
    implementation(files("libs/liquidglass-1.1.0.aar"))
    // 本地 AAR 不带 POM 里的传递依赖，必须手工补。液玻的 LiquidTracker 构造函数里就
    // new SpringForce()，缺了它一启动就是 NoClassDefFoundError: androidx.dynamicanimation.animation.SpringForce。
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")

    // 已移除：OCR 实现随 Root 页面轮询一起删除（com.google.mlkit:text-recognition-chinese）。
    // 现在所有通道拿到的都是结构化凭证（hook 的库字段、通知的 title/text、短信正文），
    // 不需要截图识别，也就不需要这个约 10MB 的模型。

    // Xposed API（内置 LSP 增强模块）
    compileOnly("de.robv.android.xposed:api:82")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}
