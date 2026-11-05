plugins { id("com.android.application") }
android {
    namespace = "io.github.mymoney.ocrfixture"
    compileSdk = 35
    defaultConfig { applicationId = providers.gradleProperty("ocrFixtureAppId").getOrElse("com.tencent.mm").also { require(it in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")) }; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "synthetic-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
