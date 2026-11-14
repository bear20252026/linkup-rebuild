# LinkUp 重建的 R8 规则：只 keep 已知反射/序列化面，其余交给优化。
# 出正式包前必须真机回归（待验 91）。

# ---- 网络栈（Retrofit 官方规则的精简版）----
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-if interface * { @retrofit2.http.* public ** *(...); }
-keep,allowobfuscation interface <1>

# ---- Gson 序列化 bean：字段名是网络契约，不混淆 ----
-keep class com.youloft.linkup.api.dto.** { *; }
-keep class io.openim.android.sdk.models.** { *; }
-keep class com.youloft.linkup.im.** { *; }
# Gson TypeToken 泛型签名
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * extends com.google.gson.reflect.TypeToken { <init>(); }

# ---- OpenIM SDK（自带反射与回调）----
-keep class io.openim.** { *; }

# ---- WebView JS 接口（addJavascriptInterface 反射调方法）----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 定位/地图的反射适配器按名字找的类（AAR 到位时不可混淆）----
-keep class com.baidu.** { *; }
-keep class com.amap.** { *; }

# ---- osmdroid ----
-dontwarn org.osmdroid.**
