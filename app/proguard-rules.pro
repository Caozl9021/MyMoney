-keep class io.github.caozl9021.mymoney.capture.** { *; }
-keep class io.github.caozl9021.mymoney.root.** { *; }

# Xposed 模块相关
-keep class io.github.caozl9021.mymoney.xposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keepclasseswithmembers class * {
    public void handleLoadPackage(...);
}

# Shared target capture protocol and provider entry point.
-keep class io.github.caozl9021.mymoney.lsp.** { *; }
