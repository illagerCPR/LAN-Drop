# LAN-Drop ProGuard 规则
# 目前 release 未开启压缩（isMinifyEnabled = false）。
# 开启压缩时，kotlinx.serialization 与 OkHttp 需要保留其元数据/反射信息：

# kotlinx.serialization：保留 @Serializable 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp：平台相关可选依赖
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
