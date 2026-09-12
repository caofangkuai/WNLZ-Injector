# Android 独立插件移植教程

将各类 Android 模块/Xposed 模块移植为独立插件格式（`info.json` + `plugin_main.dex`）的通用指南。

---

## 一、插件格式说明

最终产物是一个 ZIP 文件：

```
plugin.zip
├── info.json            # 插件元数据
├── plugin_main.dex      # 编译后的 Dalvik 字节码
├── assets/              # 内嵌资源（可选）
├── lib/                 # 原生库（可选）
└── src/                 # 源码（可选，供参考）
```

### info.json 格式

```json
{
  "name": "YourPlugin",
  "author": "@Author",
  "version": "1",
  "description": "Plugin description",
  "init": "com.example.plugin.EntryPoint"
}
```

`init` 指定入口类全限定名，该类需实现 `Application.ActivityLifecycleCallbacks` 接口，并提供以下静态方法之一：

```java
public static EntryPoint onInject(File file, Application application);
public static EntryPoint onInject(Application application);
```

其中 `File` 参数为插件 ZIP 文件本身，可用于读取内嵌的 assets 和 lib 资源。

---

## 二、有源码移植流程

### 2.1 环境准备

- Android SDK（需要 `android.jar` 用于编译）
- `d8` 编译器（Android SDK Build-Tools 自带）
- Java 8 编译目标

```bash
# 编译
javac -source 1.8 -target 1.8 \
  -cp /path/to/android.jar \
  -d build/classes \
  $(find src -name "*.java")

# 生成 dex
d8 --min-api 26 --output build/ build/classes/**/*.class
```

### 2.2 架构设计

插件运行在宿主 App 进程中，通过 `ActivityLifecycleCallbacks` 监听 Activity 生命周期，在适当时机将自定义 UI 挂载到宿主窗口。

### 2.3 关键移植步骤

#### 步骤 1：移除 Xposed 依赖

移除所有 `de.robv.android.xposed.*` 引用，将 Hook 逻辑替换为直接调用或移除。

#### 步骤 2：实现入口类

```java
public class EntryPoint implements Application.ActivityLifecycleCallbacks {
    
    public static EntryPoint onInject(File file, Application application) {
        init(file, application);
        return new EntryPoint();
    }
    
    private static void init(File file, Application app) {
        AppSettings.init(app);
            if (file != null && file.exists()) {
            extractAssetsFromZip(file, app);
            extractLibsFromZip(file, app);
        }
    }
    
    @Override
    public void onActivityResumed(Activity activity) {
        // 在这里挂载 UI，具体方式取决于原始模块的实现
    }
    
    // ... 其他生命周期回调
}
```

#### 步骤 3：处理 assets 资源

插件 ZIP 中内嵌的 assets 通过 `onInject` 的 `File` 参数读取，解压到应用私有目录：

```java
private static void extractAssetsFromZip(File zipFile, Application app) {
    File assetsDir = new File(app.getFilesDir(), "assets");
    if (!assetsDir.exists()) assetsDir.mkdirs();
    
    try (ZipFile zip = new ZipFile(zipFile)) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().startsWith("assets/")) {
                String relativePath = entry.getName().substring("assets/".length());
                if (relativePath.isEmpty()) continue;
                
                File outFile = new File(assetsDir, relativePath);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (InputStream is = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
            }
        }
    } catch (IOException e) {
        Log.e("Plugin", "Failed to extract assets: " + e.getMessage());
    }
}
```

使用时从私有目录读取：

```java
File configFile = new File(app.getFilesDir(), "assets/config.json");
```

#### 步骤 4：处理 native 库（lib/）

从 ZIP 中解压 `.so` 文件到应用私有目录后加载：

```java
private static void extractLibsFromZip(File zipFile, Application app) {
    File libDir = new File(app.getFilesDir(), "lib");
    if (!libDir.exists()) libDir.mkdirs();
    
    try (ZipFile zip = new ZipFile(zipFile)) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("lib/") && name.endsWith(".so")) {
                // 路径格式: lib/arm64-v8a/libname.so
                String[] parts = name.split("/");
                if (parts.length >= 3) {
                    String abi = parts[1];
                    String fileName = parts[2];
                    File abiDir = new File(libDir, abi);
                    abiDir.mkdirs();
                    
                    File outFile = new File(abiDir, fileName);
                    try (InputStream is = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                    outFile.setReadable(true);
                    outFile.setExecutable(true);
                }
            }
        }
    } catch (IOException e) {
        Log.e("Plugin", "Failed to extract libs: " + e.getMessage());
    }
}
```

加载 native 库：

```java
public static void loadNativeLibs(Application app) {
    File libDir = new File(app.getFilesDir(), "lib");
    String[] abis = Build.SUPPORTED_ABIS;
    
    for (String abi : abis) {
        File abiDir = new File(libDir, abi);
        if (!abiDir.exists()) continue;
        
        File[] soFiles = abiDir.listFiles((dir, name) -> name.endsWith(".so"));
        if (soFiles != null) {
            for (File so : soFiles) {
                System.load(so.getAbsolutePath());
            }
            return; // 找到匹配的 ABI 后停止
        }
    }
    
    // 回退：尝试任意可用 ABI
    File[] dirs = libDir.listFiles(File::isDirectory);
    if (dirs != null) {
        for (File dir : dirs) {
            File[] soFiles = dir.listFiles((d, name) -> name.endsWith(".so"));
            if (soFiles != null) {
                for (File so : soFiles) {
                    System.load(so.getAbsolutePath());
                }
                return;
            }
        }
    }
}
```

#### 步骤 5：目录初始化

外部存储目录使用 `mkdirs()` 逐级创建：

```java
File extDir = new File(Environment.getExternalStorageDirectory(), 
    "Android/media/" + context.getPackageName() + "/PluginName");
extDir.mkdirs();
```

### 2.4 打包

```bash
# 1. 编译
javac -source 1.8 -target 1.8 -cp android.jar -d build/classes $(find src -name "*.java")

# 2. 生成 dex
d8 --min-api 26 --output build/ build/classes/**/*.class

# 3. 准备 ZIP 内容
mkdir -p build/zip
cp info.json build/zip/
cp build/classes.dex build/zip/plugin_main.dex

# 4. 添加 assets（如有）
mkdir -p build/zip/assets
cp -r your_assets/* build/zip/assets/

# 5. 添加 native 库（如有）
mkdir -p build/zip/lib/arm64-v8a
cp your_libs/arm64-v8a/*.so build/zip/lib/arm64-v8a/

# 6. 打包
cd build/zip && zip -r /workspace/plugin.zip .

# 7. 可选：包含源码
cd project_root && zip -r /workspace/plugin.zip src/
```

---

## 三、无源码移植流程（仅 APK）

### 3.1 工具准备

| 工具 | 用途 |
|------|------|
| [apktool](https://ibotpeaches.github.io/Apktool/) | 反编译/重打包 APK |
| [jadx](https://github.com/skylot/jadx) | DEX 反编译为 Java（推荐，便于分析） |
| [dex2jar](https://github.com/pxb1988/dex2jar) + JD-GUI | 备选反编译方案 |
| [baksmali/smali](https://github.com/JesusFreke/smali) | DEX 与 smali 互转 |
| Android SDK Build-Tools | d8/签名工具 |

### 3.2 反编译 APK

```bash
# 使用 apktool 获取 smali + 资源
apktool d original.apk -o decompiled/

# 使用 jadx 获取 Java 伪代码（便于分析）
jadx -d java_source/ original.apk
```

### 3.3 分析原始代码

#### 定位入口点

- Xposed 入口：查找 `"de.robv.android.xposed"` 引用
- 初始化逻辑：查找 `handleLoadPackage`、`initZygote`
- Hook 目标：查找 `findAndHookMethod` 调用
- UI 代码：查找自定义 View、WindowManager 操作

#### 识别关键组件

1. 入口类 → 改为实现 `ActivityLifecycleCallbacks`
2. Hook 方法 → 替换为直接调用或移除
3. 配置加载 → 硬编码或从文件读取
4. UI 视图 → 保留不变

### 3.4 修改 smali 代码

#### 移除 Xposed 入口

```smali
.method public handleLoadPackage(...)V
    .locals 0
    return-void
.end method
```

#### 修改验证方法（强制返回 true）

```smali
.method public isVerified()Z
    .locals 1
    const/4 v0, 0x1
    return v0
.end method
```

#### 移除 native 加载

删除 `static` 构造块中的 `System.loadLibrary` 调用：

```smali
# 删除：
# const-string p0, "native_lib"
# invoke-static {p0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
```

#### 创建入口类

新建入口类编译为 dex 后合并到反编译目录：

```java
// EntryPoint.java
public class EntryPoint implements Application.ActivityLifecycleCallbacks {
    public static EntryPoint onInject(File file, Application app) {
        return new EntryPoint();
    }
    // ... 生命周期回调
}
```

### 3.5 合并新增代码

```bash
# 1. 编译新类
javac -source 1.8 -target 1.8 -cp android.jar EntryPoint.java

# 2. 转为 dex
d8 --min-api 26 --output . EntryPoint.class

# 3. 反编译为 smali 放入对应包目录
baksmali disassemble classes.dex -o decompiled/smali/com/example/

# 或追加为 classes2.dex/classes3.dex 放入 APK
```

### 3.6 重打包

```bash
apktool b decompiled/ -o new.apk
apksigner sign --ks keystore.jks --ks-pass pass:password new.apk
```

### 3.7 常见 smali 修改模式

#### 强制方法返回值

```smali
.method public isVerified()Z
    .locals 1
    const/4 v0, 0x1
    return v0
.end method
```

#### 调用 UI 初始化

```smali
.method public onActivityResumed(Landroid/app/Activity;)V
    .locals 1
    .param p1, "activity"
    invoke-static {p1}, Lcom/original/ui/UIManager;->attach(Landroid/app/Activity;)V
    return-void
.end method
```

#### 移除网络验证

```smali
# 将网络请求替换为直接调用成功回调
```

---

## 四、两种场景对比

| 维度 | 有源码 | 无源码（仅 APK） |
|------|--------|-----------------|
| 修改方式 | 直接编辑 Java 代码 | 编辑 smali 或用 jadx 导出后修改 |
| 难度 | 低 | 中高 |
| 重构能力 | 完全可控 | 受限于原始结构 |
| 编译工具 | javac + d8 | apktool + baksmali + smali |
| 调试方式 | Logcat + 日志 | Logcat + smali 日志注入 |
| 维护成本 | 低 | 高（每次更新需重新反编译） |

---

## 五、常见问题

### Q: 目录创建失败？

Android 11+ 使用 `Android/media/<pkg>/` 替代 `Android/data/<pkg>/`。使用 `mkdirs()` 而非 `mkdir()` 确保逐级创建。

### Q: 插件加载后闪退？

检查 `info.json` 中的 `init` 类名是否与实际类完全匹配（包括包名）。确保入口类的 `onInject` 方法签名正确。

### Q: assets 中的文件无法读取？

确认 `onInject` 的 `File` 参数指向正确的 ZIP 文件路径。解压时使用 `ZipFile` 遍历 `assets/` 前缀的条目，解压到 `getFilesDir()` 下的目录。

### Q: .so 库加载失败？

确认 ABI 目录匹配（`arm64-v8a` / `armeabi-v7a` / `x86_64`）。使用 `Build.SUPPORTED_ABIS` 获取设备 ABI 列表依次尝试。加载时使用完整路径 `System.load(absolutePath)` 而非 `System.loadLibrary(name)`。

### Q: 如何调试？

```java
Log.d("PluginTag", "Message");
adb logcat -s PluginTag
```

---

## 六、最终 ZIP 结构参考

```
plugin.zip
├── info.json
├── plugin_main.dex
├── assets/              # 可选，通过 File 参数读取
│   ├── config.json
│   └── logo.png
├── lib/                 # 可选，解压到私有目录后 System.load()
│   └── arm64-v8a/
│       └── libmynative.so
└── src/                 # 可选
    └── ...
```
