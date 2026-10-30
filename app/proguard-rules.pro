-keep class io.github.mymoney.capture.** { *; }
-keep class io.github.mymoney.root.** { *; }

# Xposed 模块相关
-keep class io.github.mymoney.xposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keepclasseswithmembers class * {
    public void handleLoadPackage(...);
}

# Shared target capture protocol and provider entry point.
-keep class io.github.mymoney.lsp.** { *; }
