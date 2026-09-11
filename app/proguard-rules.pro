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

# 保留所有继承 IInterface 的类（AIDL 生成的 Stub/Proxy 等）
-keep public interface ** extends android.os.IInterface {*;}

# Shizuku：保留 rikka.shizuku 全部成员。
# 本应用通过反射调用 Shizuku.newProcess（Rikka 13.x 中为 private），
# R8 必须保留该方法及所在类，否则运行时找不到 newProcess。
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.server.** { *; }

# 保留 ShizukuProvider（provider 依赖自动注册），防止被 R8/资源处理误删
-keep class rikka.shizuku.ShizukuProvider { *; }

# hiddenapibypass：解除非 SDK 接口访问限制，注入流程强依赖，release 下必须保留。
# 该库内部通过 Unsafe/反射访问 VMRuntime，混淆或裁剪会导致豁免失效。
-keep class org.lsposed.hiddenapibypass.** { *; }

# android.* 隐藏 API 桩类必须保留原始类名。
# 这些类运行时会被 framework.jar 中的同名类（父类加载器优先）覆盖，仅用于编译期类型检查。
# 若被 R8 重命名（实测 IPackageManager$Stub -> b.b），Class.forName("android.content.pm.Xxx$Stub")
# 的字符串会被同步改写成混淆名，于是加载到 app 内的桩类而非系统类：
#   1. asInterface 位于 Kotlin companion，Stub 上无该方法 -> NoSuchMethodException；
#   2. IPackageInstaller::class.java 等类字面量也指向 app 桩类，导致 PackageInstaller 构造函数匹配失败。
-keep class android.content.** { *; }
