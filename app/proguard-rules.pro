# Add project specific ProGuard rules here.

# 保留应用自身代码
-keep class com.wunelezi.injector.** { *; }

# 保留 AndroidManifest 中声明的外部服务（StartAnyWhere 账号认证）
-keep class com.cfks.startanywhere.** { *; }

# 说明：authenticator.xml 仅被 AndroidManifest 的 meta-data 引用、Java 从不引用 R.xml.authenticator，
# shrinkResources 会误删它，导致 AuthService 无法注册、StartAnyWhere 失效。
# 处理方式为在 build.gradle 中关闭 shrinkResources（见 minify/shrink 配置块）。

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
