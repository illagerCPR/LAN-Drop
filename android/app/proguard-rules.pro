# LAN-Drop ProGuard 规则
# release 已开启 R8 收缩（isMinifyEnabled = true）+ 资源收缩。
# 本项目无 Java 反射调用；协议 DTO 全部由 kotlinx.serialization 编译期生成序列化器，
# 保留其生成物即可；Room/OkHttp/Compose 自带 consumer 规则。

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
