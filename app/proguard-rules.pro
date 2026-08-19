# Add project specific ProGuard rules here.

# 保留应用自身代码
-keep class com.wunelezi.injector.** { *; }

# 保留 AndroidManifest 中声明的外部服务（StartAnyWhere 账号认证）
-keep class com.cfks.startanywhere.** { *; }

# Kotlin 元数据
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keep,allowobfuscation,allowshrinking class kotlin.cachedKotlin { *; }

# ViewBinding 生成的绑定类
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# 保留构造器，防止反射实例化失败
-keepclassmembers class * {
    public <init>();
}
