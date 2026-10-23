-keep class com.example.autobook.capture.** { *; }
-keep class com.example.autobook.root.** { *; }

# Xposed 模块相关
-keep class com.example.autobook.xposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keepclasseswithmembers class * {
    public void handleLoadPackage(...);
}
