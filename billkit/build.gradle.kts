plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.billkit"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = true }

    sourceSets.getByName("test") {
        // spec 数据（js_common.js / rules_all.json / categories.json / assets.json）既是
        // Android assets，也是离线重放测试的输入。让 test classpath 直接读同一份文件，
        // 避免把 300KB 规则复制两份、也避免测试与运行时看到不同版本。
        resources.srcDir("src/main/assets")
    }
}

dependencies {
    // StateFlow 出现在公开 API（DebugBus）里，所以用 api 而不是 implementation
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // 原创本地规则使用 Rhino 解释执行，无需原生 .so。
    implementation("org.mozilla:rhino:1.7.15")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

// 源脚本与最终资产必须一致，避免只改可读源却忘记更新随包文件。
val verifyOriginalRules by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", layout.projectDirectory.file("rules/generate.py").asFile.absolutePath, "--check")
}
tasks.named("preBuild") { dependsOn(verifyOriginalRules) }
