# :billkit 的 consumer rules

# Rhino 规则引擎：JS 源码里通过字符串索引访问对象属性，且引擎内部大量使用反射
# 与动态类型分派，R8 的静态分析看不到这些调用点。
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
# Rhino 只在 tools.shell / tools.debugger 里用到 AWT/Swing，Android 上没有这些类；
# 我们不会加载这两个包，但 R8 仍会顺着引用去解析。
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.script.**
# Android 的 java.beans 只提供了 PropertyChangeListener/PropertyChangeEvent 等 6 个类，
# 缺 Introspector/BeanInfo/PropertyDescriptor（Rhino 的 JavaToJSONConverters 才用到）。
# 规则引擎不走 Java→JS 的 bean 转换，因此该路径不会被加载。
-dontwarn java.beans.Introspector
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.IntrospectionException
