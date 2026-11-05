# JNI resolves these upstream Java names/members directly.
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
# Same-image benchmark uses reflection for the adapter constructor and entry point.
-keep class io.github.mymoney.ocr.research.V6ResearchEngine { public *; }
