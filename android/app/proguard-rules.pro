# ML Kit / kotlinx.serialization 在 debug 构建中不混淆；保留规则供 release 使用。
-keep class com.google.mlkit.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
