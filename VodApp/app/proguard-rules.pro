# Moshi 使用反射适配 Kotlin 数据类，需要保留构造函数与属性名
-keepclassmembers class com.youqu.vod.data.model.** {
    <init>(...);
    <fields>;
}
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontnote retrofit2.**
-keepattributes Signature
-keepattributes *Annotation*
