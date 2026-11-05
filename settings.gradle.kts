pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "MyMoney"
include(":app")
include(":billkit")

// Pinned offline production OCR engine; no model downloads at runtime.
include(":ocr-v6")
project(":ocr-v6").projectDir = file("ocr-research/v6")

// Optional synthetic payment surface; never packaged with the production app.
if (providers.gradleProperty("ocrFixture").orNull == "true") {
    include(":ocr-fixture")
    project(":ocr-fixture").projectDir = file("ocr-research/fixture")
}
