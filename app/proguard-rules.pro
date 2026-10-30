-keep class com.example.mymoney.capture.** { *; }
-keep class com.example.mymoney.root.** { *; }

# Xposed 模块相关
-keep class com.example.mymoney.xposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keepclasseswithmembers class * {
    public void handleLoadPackage(...);
}

# Shared target capture protocol and provider entry point.
-keep class com.example.mymoney.lsp.** { *; }
