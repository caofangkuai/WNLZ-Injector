# 无能乐子注入器 (WNLZ-Injector)

一款 Android DEX 注入工具，针对使用网易 X19 SDK 的游戏应用，通过多种方式将自定义 DEX 文件注入到目标应用的 `app_ntp0/<版本>/.unzip/` 目录中，实现模块化扩展。

## 功能概览

- **多种注入方式**：支持 StartAnyWhere、CVE-2024-0044、Root 共五种注入路径
- **插件管理**：通过 Content URI 导入/删除/查看模块（zip 格式，含 `info.json`）
- **应用选择**：长按包名输入框弹出已安装非系统应用列表
- **版本检测**：自动检测目标应用 `versionName_versionCode`，支持手动覆盖
- **设备兼容性检测**：根据 SDK 版本和安全补丁日期判断注入方式是否可用
- **权限管理**：一键释放所有持久化 URI 权限
- **详细错误日志**：注入失败时展示完整异常堆栈，支持复制到剪贴板

## 注入方式

| 方式 | 名称 | 原理 | 系统要求 |
|------|------|------|----------|
| StartAnyWhere (NgWebviewActivity) | 利用 Intent 重定向启动任意 Activity | 通过伪造 AccountAuthenticator 响应，以系统身份拉起目标应用的 NgWebviewActivity，携带 URI 授权 flags | Android 11-13，安全补丁 < 2023-03-01 |
| StartAnyWhere (AssistActivity) | 同上，目标改为腾讯 AssistActivity | 通过 PendingIntent + Bundle 传递授权参数 | 同上 |
| CVE-2024-0044 (pm install) | 利用 Shizuku 执行 `pm install -i` | 通过 Shizuku 以 shell 身份执行 pm install，使用伪造 installer 包名 `mcinject` 实现 run-as 效果 | Android 12-14，安全补丁 < 2024-10-01 |
| CVE-2024-0044 (PackageInstaller) | 利用 Shizuku 直接调用 PackageInstaller API | 通过反射获取系统 IPackageManager → IPackageInstaller，经 ShizukuBinderWrapper 提权后静默安装 | 同上 |
| Root 注入 | 以 Root 身份直接替换 DEX | 通过 `su` 执行 shell 脚本，复制 DEX 并还原原文件权限/所有者/修改时间 | 设备已 Root |

## 项目结构

```
WNLZ-Injector/
├── app/                          # 主应用模块
│   ├── src/main/
│   │   ├── AndroidManifest.xml   # 清单文件（声明 AuthService、ShizukuProvider）
│   │   ├── aidl/                 # AIDL 接口定义（编译期关闭，仅用于参考）
│   │   │   └── android/content/
│   │   │       ├── IIntentReceiver.aidl
│   │   │       └── IIntentSender.aidl
│   │   ├── assets/
│   │   │   ├── cve-2024-0044.apk       # CVE 注入所需辅助 APK
│   │   │   └── cve-2024-0044.apk.idsig  # APK 签名方案 v4
│   │   ├── java/
│   │   │   ├── android/content/         # Kotlin 桩类（IIntentSender/IIntentReceiver）
│   │   │   │   └── pm/                  # IPackageManager/IPackageInstaller/IPackageInstallerSession 桩
│   │   │   ├── com/cfks/startanywhere/  # StartAnyWhere 核心实现
│   │   │   │   ├── StartAnyWhere.java   # Parcel 伪造 + 任意 Activity 启动
│   │   │   │   └── AuthService.java     # AccountAuthenticator 服务
│   │   │   └── com/wunelezi/injector/   # 应用主包
│   │   │       ├── MainActivity.kt       # 主界面 + 注入流程入口
│   │   │       ├── inject/               # 注入处理器
│   │   │       │   ├── CvePmHandler.kt            # CVE-2024-0044 (pm install)
│   │   │       │   ├── CvePackageInstallerHandler.kt # CVE-2024-0044 (PackageInstaller)
│   │   │       │   ├── PackageInstallerShizuku.kt  # Shizuku PackageInstaller 封装
│   │   │       │   ├── ShizukuExecutor.kt          # Shizuku 命令执行器
│   │   │       │   └── DexDownloader.kt             # DEX 下载/解压/写入
│   │   │       ├── model/               # 数据模型
│   │   │       │   ├── InjectionMethod.kt  # 注入方式枚举（含设备兼容性判断）
│   │   │       │   ├── ModuleInfo.kt        # 模块信息
│   │   │       │   ├── AppInfo.kt          # 应用信息
│   │   │       │   ├── ImportResult.kt     # 导入结果
│   │   │       │   └── LoadResult.kt       # 加载结果
│   │   │       ├── util/                # 工具类
│   │   │       │   ├── PluginManager.kt   # 插件管理（Content URI 方案）
│   │   │       │   └── UriHelper.kt       # Content URI 读写工具
│   │   │       ├── adapter/              # RecyclerView 适配器
│   │   │       │   ├── AppAdapter.kt      # 应用列表
│   │   │       │   ├── ModuleAdapter.kt   # 模块列表（含批量选择删除）
│   │   │       │   └── MethodAdapter.kt   # 注入方式列表
│   │   │       └── widget/
│   │   │           └── MethodSelectorView.kt  # 注入方式选择组件
│   │   └── res/                         # 资源文件
│   │       ├── layout/                  # 布局
│   │       ├── values/strings.xml       # 字符串（简体中文）
│   │       ├── xml/authenticator.xml    # AccountAuthenticator 配置
│   │       └── ...
│   ├── build.gradle                     # 应用构建配置
│   ├── proguard-rules.pro              # ProGuard 规则
│   └── release.jks                     # 签名密钥
├── hidden-api/                          # 隐藏 API 桩模块（compileOnly）
│   └── src/main/java/
│       ├── android/content/pm/          # IPackageManager 等隐藏接口桩
│       ├── android/os/ServiceManager.java
│       └── com/android/modules/utils/   # ParceledListSlice 桩
├── scripts/
│   ├── install-hooks.sh                # 安装 git hooks 脚本
│   └── pre-push                        # pre-push hook（禁止 push tag）
├── build.gradle                         # 根构建文件
├── settings.gradle                      # 项目设置
└── gradle.properties                    # Gradle 属性
```

## 核心技术原理

### StartAnyWhere 注入

利用 Android AccountAuthenticator 机制，通过 `AuthService` 注册一个自定义账号类型。`StartAnyWhere.pullSpecialActivity()` 方法手动构造 Parcel 数据，伪造一个包含目标 Intent 的 Bundle 响应，然后启动系统的 `ChooseTypeAndAccountActivity`。系统在调用 `addAccount` 时，`AuthService` 返回预制的 Bundle，其中包含的 Intent 被系统以自身身份启动，从而实现：

1. 以系统身份拉起目标应用的指定 Activity
2. Intent 中携带 `FLAG_GRANT_READ/WRITE/PERSISTABLE_URI_PERMISSION`，使目标应用获得对 DEX 文件 URI 的读写权限

### CVE-2024-0044 注入

利用 Android `pm install -i <installer_package>` 的 installer 身份伪造漏洞（CVE-2024-0044），通过 Shizuku 以 shell 身份执行安装命令，将 installer 包名设为 `mcinject`（目标应用的 run-as 包名），从而获得以目标应用身份执行命令的能力。之后将 DEX 文件复制到目标应用的 `app_ntp0/<版本>/.unzip/` 目录。

PackageInstaller 变体则通过反射直接调用系统 `IPackageManager.getPackageInstaller()` → `PackageInstaller`，经 `ShizukuBinderWrapper` 包装后实现静默安装。

### Root 注入

通过 `su -c` 执行 shell 脚本，直接将 DEX 文件复制到目标目录，并使用 `stat`/`chmod`/`chown`/`touch` 还原原文件的权限、所有者、用户组和修改时间，做到外观无痕。

### 插件管理

采用纯 Content URI 方案，通过目标应用暴露的 `content://com.netease.x19.widget_file_provider/widget_external_files/` 访问其 `files` 目录：

- **读取**：从 `plugins.txt` 获取 zip 名称列表，遍历每个 zip 读取 `info.json` 解析模块名和作者
- **导入**：SAF 选择文件 → 通过 Content URI 写入为 `UUID.zip` → 更新 `plugins.txt`
- **删除**：从 `plugins.txt` 移除条目 → 通过 `ContentResolver.delete` 删除 zip

## 构建环境

| 项目 | 版本 |
|------|------|
| Android Gradle Plugin | 8.0.2 |
| Kotlin | 1.9.22 |
| compileSdk | 34 |
| minSdk | 24 |
| targetSdk | 34 |
| Java | 17 |

### 关键依赖

- `dev.rikka.shizuku:api:13.1.5` + `dev.rikka.shizuku:provider:13.1.5` — Shizuku API
- `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` — 隐藏 API 限制绕过
- `hidden-api`（本地模块，compileOnly）— 系统隐藏接口桩

### 构建说明

- 项目使用阿里云 Maven 镜像，适合国内网络环境
- `build.gradle` 中 `aidl = false`，避免 AIDL 生成的 Java 类与 Kotlin 桩类冲突
- release 构建开启 `minifyEnabled` 但关闭 `shrinkResources`，防止 `authenticator.xml` 被误删
- ProGuard 规则保留了 `rikka.shizuku.**`、`org.lsposed.hiddenapibypass.**`、`android.content.**` 等关键类

## 使用方法

1. **输入包名**：在主界面输入目标应用包名，或长按输入框从应用列表选择
2. **选择注入方式**：点击注入方式卡片，在弹出列表中选择合适的方式（不支持的会标红）
3. **注入**：点击"注入"按钮，等待下载并写入 DEX 文件
4. **管理模块**：点击"注入模块"卡片，导入 zip 格式的插件模块（需包含 `info.json`）
5. **重启游戏**：注入完成后重启目标游戏即可生效

### 版本号设置

菜单栏可设置自定义官包版本号（格式：`版本名_版本号`），留空则自动检测已安装应用的版本。当目标应用未安装或版本检测失败时可手动指定。

## DEX 文件来源

DEX 文件从 GitHub Releases 下载：
```
https://github.com/caofangkuai/WNLZ-Injector-dex/releases/download/<versionSegment>/dex.zip
```
其中 `<versionSegment>` 为 `versionName_versionCode` 格式。StartAnyWhere 方式还会通过 WebView 加载远程注入页面。

## 开发约定

- 本仓库**禁止打 tag**，所有版本号在 commit 信息中描述（通过 `scripts/pre-push` hook 拦截）
- 字符串资源仅保留简体中文（`resConfigs "zh-rCN"`）
- 签名配置统一使用 `release.jks`（debug 和 release 均使用同一签名）

## 许可证

本项目仅供学习和研究使用。
